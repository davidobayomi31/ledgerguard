// LedgerGuard web app: a small front end over the REST API.
// No framework: it calls the same endpoints Swagger shows, with a friendlier face.

const TOKEN_KEY = 'ledgerguard.token'
const CONTACTS_KEY = 'ledgerguard.contacts'
const SANDBOX_KEY = 'ledgerguard.sandbox'
const state = { user: null, accounts: [], selected: null, lastRequest: null }

const $ = (sel, root = document) => root.querySelector(sel)
const cad = new Intl.NumberFormat('en-CA', { style: 'currency', currency: 'CAD' })
const money = (s) => cad.format(Number(s))
const when = (iso) => new Date(iso).toLocaleString('en-CA', { dateStyle: 'medium', timeStyle: 'short' })
const shortId = (id) => id.slice(0, 8)
const AMOUNT = /^\d{1,13}(\.\d{1,2})?$/

const REASONS = {
  INSUFFICIENT_FUNDS: 'Not enough money in that account.',
  BLOCKED_BY_REVIEW: 'Blocked by fraud review.',
}
const STATUS = {
  SETTLED: { css: 'settled', label: 'Settled' },
  REJECTED: { css: 'rejected', label: 'Rejected' },
  HELD: { css: 'held', label: 'Held for review' },
  BLOCKED: { css: 'blocked', label: 'Blocked' },
}
const isAnalyst = () => state.user?.role === 'ANALYST' || state.user?.role === 'ADMIN'

// ── Storage (falls back to memory if the browser blocks it) ────────
const store = {
  get() { try { return sessionStorage.getItem(TOKEN_KEY) } catch { return state.token } },
  set(v) { state.token = v; try { sessionStorage.setItem(TOKEN_KEY, v) } catch {} },
  clear() {
    state.token = null
    state.sandbox = null
    try { [TOKEN_KEY, CONTACTS_KEY, SANDBOX_KEY].forEach((k) => sessionStorage.removeItem(k)) } catch {}
  },
}

// Demo only: the throwaway people in this sandbox ({ name, token, accountId, accountName }).
// Holding their tokens lets the visitor switch between them in one tab. Kept per tab, like the session.
const MAX_PEOPLE = 7
const contacts = {
  list() { try { return JSON.parse(sessionStorage.getItem(CONTACTS_KEY)) || [] } catch { return state.contacts || [] } },
  save(list) { state.contacts = list; try { sessionStorage.setItem(CONTACTS_KEY, JSON.stringify(list)) } catch {} },
}

// ── API ────────────────────────────────────────────────────────────
class ApiError extends Error {
  constructor(status, body) {
    super(body?.detail || `Request failed (${status}).`)
    this.status = status
    this.code = body?.code
  }
}

async function api(path, { method = 'GET', body, key, as } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = as ?? store.get()
  if (token) headers.Authorization = `Bearer ${token}`
  if (key) headers['Idempotency-Key'] = key
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (res.status === 401 && token && !as) {
    signOut('Your session expired. Please sign in again.')
    throw new ApiError(401, { detail: 'Session expired.' })
  }
  if (!res.ok) throw new ApiError(res.status, data)
  return { data, status: res.status, replayed: res.headers.get('Idempotent-Replayed') === 'true' }
}

// ── Auth view ──────────────────────────────────────────────────────
let mode = 'login'
function setMode(next) {
  mode = next
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.mode === mode))
  document.querySelectorAll('.register-only').forEach((el) => { el.hidden = mode !== 'register' })
  $('#auth-submit').textContent = mode === 'login' ? 'Sign in' : 'Create account'
  $('#auth-form [name=password]').autocomplete = mode === 'login' ? 'current-password' : 'new-password'
  $('#auth-error').textContent = ''
}
document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => setMode(t.dataset.mode)))

$('#auth-form').addEventListener('submit', async (e) => {
  e.preventDefault()
  const f = new FormData(e.target)
  const email = f.get('email').trim()
  const password = f.get('password')
  const fullName = (f.get('fullName') || '').trim()
  const error = $('#auth-error')

  if (!email.includes('@')) return (error.textContent = 'Enter a valid email address.')
  if (mode === 'register') {
    if (!fullName) return (error.textContent = 'Enter your name.')
    if (password.length < 12) return (error.textContent = 'Password must be at least 12 characters.')
    if (new TextEncoder().encode(password).length > 72) return (error.textContent = 'Password must be at most 72 characters.')
  } else if (!password) {
    return (error.textContent = 'Enter your password.')
  }

  const button = $('#auth-submit')
  button.disabled = true
  try {
    const path = mode === 'login' ? '/api/auth/login' : '/api/auth/register'
    const body = mode === 'login' ? { email, password } : { email, password, fullName }
    const { data } = await api(path, { method: 'POST', body })
    store.set(data.accessToken)
    state.user = data.user
    e.target.reset()
    await showApp()
  } catch (err) {
    error.textContent = err.status === 400 ? 'Check your details and try again.' : err.message
  } finally {
    button.disabled = false
  }
})

function signOut(message) {
  store.clear()
  state.user = null
  state.selected = null
  $('#app-view').hidden = true
  $('#auth-view').hidden = false
  setMode('login')
  if (message) $('#auth-error').textContent = message
}
$('#sign-out').addEventListener('click', () => signOut())

// ── Dashboard ──────────────────────────────────────────────────────
async function showApp() {
  if (!state.user) state.user = (await api('/api/auth/me')).data
  $('#user-name').textContent = isAnalyst() ? `${state.user.fullName} · Fraud analyst` : state.user.fullName
  document.body.dataset.role = isAnalyst() ? 'ANALYST' : 'CUSTOMER'
  $('#auth-view').hidden = true
  $('#app-view').hidden = false
  await refresh()
}

async function refresh() {
  if (isAnalyst()) {
    await Promise.all([renderAnalyst(), renderPeople()])
    return
  }
  const [accounts, transfers] = await Promise.all([api('/api/accounts'), api('/api/transfers?limit=20')])
  state.accounts = accounts.data
  renderAccounts()
  renderActivity(transfers.data)
  if (state.selected && state.accounts.some((a) => a.id === state.selected)) await renderStatement()
  await renderPeople()
}

// ── People in the demo sandbox ────────────────────────────────────
async function renderPeople() {
  const people = contacts.list()
  const panel = $('#people-panel')
  panel.hidden = !people.length
  if (!people.length) return
  $('#people-count').textContent = `${people.length} of ${MAX_PEOPLE}`
  const me = store.get()
  const totals = await Promise.all(people.map(async (p) => {
    if (p.role === 'ANALYST') return 'analyst'
    try {
      const { data } = await api('/api/accounts', { as: p.token })
      return data.reduce((sum, a) => sum + Number(a.balance), 0)
    } catch { return null }
  }))
  $('#people').replaceChildren(...people.map((p, i) => {
    const li = document.createElement('li')
    li.className = 'person' + (p.token === me ? ' current' : '')
    li.innerHTML = '<div><div class="person-name"></div><div class="muted small person-total"></div></div>'
    $('.person-name', li).textContent = p.name
    $('.person-total', li).textContent = totals[i] === 'analyst' ? 'Fraud analyst'
      : totals[i] === null ? 'Session expired' : cad.format(totals[i])
    if (p.token === me) {
      const tag = document.createElement('span')
      tag.className = 'pill settled'
      tag.textContent = 'Viewing'
      li.append(tag)
    } else {
      const b = document.createElement('button')
      b.className = 'btn small'
      b.textContent = 'Switch'
      b.setAttribute('aria-label', `Switch to ${p.name}`)
      b.addEventListener('click', () => switchTo(p))
      li.append(b)
    }
    return li
  }))
  const full = people.length >= MAX_PEOPLE
  const form = $('#add-person')
  form.name.disabled = full
  $('button', form).disabled = full
  form.name.placeholder = full ? `Sandbox is full (${MAX_PEOPLE} people)` : 'Name, e.g. Priya Singh'
}

async function switchTo(person) {
  store.set(person.token)
  state.user = null
  state.selected = person.accountId ?? null
  try {
    await showApp()
    notify('info', person.role === 'ANALYST'
      ? `You're now ${person.name}, a fraud analyst. Approve or block the held payments below, then switch back to a customer to see the result.`
      : `You're now viewing as ${person.name}. Send money to anyone in the sandbox, then switch to them to see it arrive.`)
  } catch {
    notify('bad', `Couldn't switch to ${person.name}. Their demo session may have expired; start a new demo.`)
  }
}

$('#add-person').addEventListener('submit', async (e) => {
  e.preventDefault()
  const form = e.target
  const error = $('#people-error')
  const name = form.name.value.trim()
  error.textContent = ''
  if (name.length < 2) return (error.textContent = 'Enter a name.')
  const people = contacts.list()
  if (people.length >= MAX_PEOPLE) return (error.textContent = `The sandbox holds up to ${MAX_PEOPLE} people.`)
  if (people.some((p) => p.name.toLowerCase() === name.toLowerCase())) return (error.textContent = 'Someone with that name is already here.')
  const button = $('button', form)
  button.disabled = true
  try {
    const person = await createDemoPerson(name, '500.00')
    contacts.save([...contacts.list(), person])
    form.reset()
    notify('good', `Added ${name} with $500.00 of test money. Pick them under "Send money", or switch to them.`)
    await renderPeople()
  } catch (err) {
    error.textContent = `Couldn't add ${name}: ${err.message}`
  } finally {
    button.disabled = contacts.list().length >= MAX_PEOPLE
  }
})

function renderAccounts() {
  const total = state.accounts.reduce((sum, a) => sum + Number(a.balance), 0)
  $('#total-balance').textContent = cad.format(total)
  const box = $('#accounts')
  if (!state.accounts.length) {
    box.innerHTML = '<div class="empty">No accounts yet. Click <b>Open account</b> to create your first one.</div>'
    return
  }
  box.replaceChildren(...state.accounts.map((a) => {
    const card = document.createElement('div')
    card.className = 'account' + (a.id === state.selected ? ' selected' : '')
    card.tabIndex = 0
    card.setAttribute('role', 'button')
    card.innerHTML = `<span class="name"></span><span class="balance"></span>
      <span class="id">ID ${shortId(a.id)}… <button class="copy" type="button">Copy full ID</button></span>`
    $('.name', card).textContent = a.name
    $('.balance', card).textContent = money(a.balance)
    $('.copy', card).addEventListener('click', async (e) => {
      e.stopPropagation()
      try { await navigator.clipboard.writeText(a.id); e.target.textContent = 'Copied' } catch { prompt('Account ID:', a.id) }
    })
    const select = () => { state.selected = a.id; renderAccounts(); renderStatement() }
    card.addEventListener('click', select)
    card.addEventListener('keydown', (e) => { if (e.key === 'Enter') select() })
    return card
  }))
}

const mine = (id) => state.accounts.find((a) => a.id === id)

function describe(t) {
  const from = mine(t.fromAccountId)
  const to = mine(t.toAccountId)
  if (t.type === 'DEPOSIT') return { title: `Test deposit to ${to?.name ?? 'account'}`, sign: '+' }
  if (from && to) return { title: `${from.name} → ${to.name}`, sign: '' }
  if (from) return { title: `Sent to ${t.toName ?? 'another customer'} from ${from.name}`, sign: '−' }
  return { title: `Received from ${t.fromName ?? 'another customer'} into ${to?.name ?? 'account'}`, sign: '+' }
}

function renderActivity(transfers) {
  const list = $('#activity')
  if (!transfers.length) {
    list.innerHTML = '<li class="muted">No activity yet.</li>'
    return
  }
  list.replaceChildren(...transfers.map((t) => {
    const { title, sign } = describe(t)
    const li = document.createElement('li')
    const st = STATUS[t.status] ?? { css: '', label: t.status }
    const settled = t.status === 'SETTLED'
    li.innerHTML = `<div class="what"><div class="title"></div><div class="when"></div></div>
      <div><span class="amount"></span><span class="pill ${st.css}">${st.label}</span></div>`
    $('.title', li).textContent = title + (t.memo && t.type !== 'DEPOSIT' ? ` · ${t.memo}` : '')
    $('.when', li).textContent = when(t.createdAt) + (t.failureReason ? ` · ${REASONS[t.failureReason] ?? t.failureReason}` : '')
    const amount = $('.amount', li)
    amount.textContent = `${settled ? sign : ''}${money(t.amount)}`
    if (sign === '+' && settled) amount.classList.add('in')
    return li
  }))
}

async function renderStatement() {
  const account = mine(state.selected)
  if (!account) return
  $('#statement-title').textContent = `Statement: ${account.name}`
  $('#statement-hint').textContent = 'Every line is one ledger entry. The balance is their sum.'
  const { data } = await api(`/api/accounts/${account.id}/entries?size=20`)
  const list = $('#statement')
  if (!data.items.length) {
    list.innerHTML = '<li class="muted">No entries yet.</li>'
    return
  }
  list.replaceChildren(...data.items.map((e) => {
    const li = document.createElement('li')
    const credit = Number(e.amount) > 0
    li.innerHTML = `<div class="what"><div>${credit ? 'Credit' : 'Debit'}</div><div class="when"></div></div><span class="amount"></span>`
    $('.when', li).textContent = `${when(e.createdAt)} · transfer ${shortId(e.transferId)}…`
    const amount = $('.amount', li)
    amount.textContent = `${credit ? '+' : '−'}${money(Math.abs(Number(e.amount)))}`
    if (credit) amount.classList.add('in')
    return li
  }))
}

// ── Fraud analyst view ────────────────────────────────────────────
const ACTION_LABELS = {
  HELD: 'Held', APPROVED: 'Approved', BLOCKED: 'Blocked', REJECTED_ON_APPROVAL: 'Approved, but funds gone',
}

async function renderAnalyst() {
  const [queue, log] = await Promise.all([api('/api/reviews'), api('/api/reviews/events?limit=30')])
  $('#held-count').textContent = `${queue.data.length} waiting`

  const box = $('#review-queue')
  if (!queue.data.length) {
    box.innerHTML = '<div class="empty">Nothing waiting. Switch to a customer and send someone new $500 or more to see a payment get held.</div>'
  } else {
    box.replaceChildren(...queue.data.map(({ transfer: t, riskScore, signals }) => {
      const card = document.createElement('article')
      card.className = 'review'
      card.innerHTML = `
        <div class="review-top">
          <div><div class="review-who"></div><div class="muted small review-when"></div></div>
          <div style="text-align:right"><div class="review-amount"></div><span class="score" title="Risk score; 60 or more is held"></span></div>
        </div>
        <ul class="signals"></ul>
        <form class="decide">
          <input name="note" maxlength="280" placeholder="Note for the audit log (optional)" aria-label="Review note">
          <button class="btn primary" value="approve">Approve</button>
          <button class="btn danger" value="block">Block</button>
        </form>`
      $('.review-who', card).textContent = `${t.fromName} → ${t.toName}`
      $('.review-when', card).textContent = `${when(t.createdAt)}${t.memo ? ` · “${t.memo}”` : ''}`
      $('.review-amount', card).textContent = money(t.amount)
      $('.score', card).textContent = `Risk ${riskScore}`
      $('.signals', card).replaceChildren(...signals.map((sig) => {
        const li = document.createElement('li')
        li.innerHTML = '<b></b> <span></span>'
        $('b', li).textContent = `+${sig.points}`
        $('span', li).textContent = sig.description
        return li
      }))
      $('form', card).addEventListener('submit', (e) => decide(e, t))
      return card
    }))
  }

  const list = $('#audit-log')
  if (!log.data.length) {
    list.innerHTML = '<li class="muted">No decisions yet.</li>'
    return
  }
  list.replaceChildren(...log.data.map((e) => {
    const li = document.createElement('li')
    li.innerHTML = `<div class="what"><div class="title"></div><div class="when"></div></div>
      <div><span class="amount"></span><span class="pill ${e.action.toLowerCase()}">${ACTION_LABELS[e.action] ?? e.action}</span></div>`
    $('.title', li).textContent = `${e.fromName} → ${e.toName}` + (e.note ? ` · “${readableNote(e.note)}”` : '')
    $('.when', li).textContent = `${when(e.createdAt)} · by ${e.actorName}`
    $('.amount', li).textContent = money(e.amount)
    return li
  }))
}

// The system's own note reads "Risk score 70: LARGE_AMOUNT,NEW_PAYEE"; show the codes as words.
const SIGNAL_WORDS = {
  LARGE_AMOUNT: 'large amount', NEW_PAYEE: 'new recipient', HIGH_VELOCITY: 'many payments in minutes', DRAINS_ACCOUNT: 'empties the account',
}
const readableNote = (note) => note.replace(/[A-Z_]{6,}/g, (code) => SIGNAL_WORDS[code] ?? code).replace(/,(?=\S)/g, ', ')

async function decide(e, t) {
  e.preventDefault()
  const action = e.submitter?.value
  if (!action) return
  const form = e.target
  form.querySelectorAll('button').forEach((b) => { b.disabled = true })
  const note = form.note.value.trim()
  try {
    const { data } = await api(`/api/reviews/${t.id}/${action}`, { method: 'POST', body: note ? { note } : {} })
    const result = data.transfer.status
    if (result === 'SETTLED') notify('good', `Approved: ${money(t.amount)} moved from ${t.fromName} to ${t.toName}.`)
    else if (result === 'BLOCKED') notify('bad', `Blocked: no money moved. ${t.fromName} will see the payment as blocked.`)
    else notify('warn', `Approved, but ${t.fromName} no longer has enough money, so the payment was rejected.`)
  } catch (err) {
    notify('bad', err.code === 'ALREADY_REVIEWED' ? 'Someone already reviewed this payment.' : err.message)
  }
  await refresh()
}

// ── Notices ────────────────────────────────────────────────────────
function notify(kind, message, { retry = false } = {}) {
  const box = $('#notice')
  box.className = `notice ${kind}`
  box.innerHTML = '<p></p>'
  $('p', box).textContent = message
  if (retry) {
    const b = document.createElement('button')
    b.className = 'btn small'
    b.textContent = 'Retry this exact request'
    b.title = 'Sends the same request with the same Idempotency-Key, like a phone resending after losing signal.'
    b.addEventListener('click', retryLast)
    box.append(b)
  }
  box.hidden = false
}

async function retryLast() {
  const r = state.lastRequest
  if (!r) return
  try {
    const { replayed } = await api(r.path, { method: 'POST', body: r.body, key: r.key })
    notify('info', replayed
      ? 'Replayed: the server recognised this request\'s key and returned the original result. No money moved twice.'
      : 'Sent again.')
    await refresh()
  } catch (err) {
    notify('bad', err.message)
  }
}

function reportTransfer(t, verb) {
  if (t.status === 'REJECTED') {
    notify('bad', `Not sent: ${REASONS[t.failureReason] ?? t.failureReason} The attempt is still recorded.`)
  } else if (t.status === 'HELD') {
    notify('warn', `Held for review: this ${money(t.amount)} payment to ${t.toName} looks unusual, so a fraud analyst will check it before any money moves. Switch to the analyst to decide.`, { retry: true })
  } else {
    const toSomeoneElse = verb === 'Sent' && !mine(t.toAccountId)
    notify('good', `${verb} ${money(t.amount)}${toSomeoneElse ? ` to ${t.toName}` : ''}.`, { retry: true })
  }
}

// ── Dialogs ────────────────────────────────────────────────────────
function fillAccountSelect(select, { exclude, other = false } = {}) {
  const options = state.accounts
    .filter((a) => a.id !== exclude)
    .map((a) => new Option(`${a.name} (${money(a.balance)})`, a.id))
  if (other) options.push(new Option("Someone else's account…", 'other'))
  select.replaceChildren(...options)
}

document.querySelectorAll('[data-open]').forEach((b) => b.addEventListener('click', () => {
  const which = b.dataset.open
  if (which !== 'open' && !state.accounts.length) {
    return notify('info', 'Open an account first.')
  }
  const dlg = $(`#dlg-${which}`)
  const form = $('form', dlg)
  form.reset()
  $('.form-error', form).textContent = ''
  if (which === 'deposit') fillAccountSelect(form.accountId)
  if (which === 'send') {
    fillAccountSelect(form.fromAccountId)
    if (state.selected) form.fromAccountId.value = state.selected
    syncSendTargets(form)
  }
  dlg.showModal()
}))

function syncSendTargets(form) {
  fillAccountSelect(form.toChoice, { exclude: form.fromAccountId.value })
  const select = form.toChoice
  const people = contacts.list().filter((p) => p.token !== store.get() && p.role !== 'ANALYST')
  if (people.length) {
    const group = document.createElement('optgroup')
    group.label = 'Other people'
    people.forEach((c) => group.append(new Option(`${c.name} (${c.accountName})`, c.accountId)))
    select.append(group)
  }
  select.append(new Option("Someone else's account (paste ID)…", 'other'))
  $('.other-account', form).hidden = select.value !== 'other'
}
const sendForm = $('#dlg-send form')
sendForm.fromAccountId.addEventListener('change', () => syncSendTargets(sendForm))
sendForm.toChoice.addEventListener('change', () => { $('.other-account', sendForm).hidden = sendForm.toChoice.value !== 'other' })

document.querySelectorAll('dialog form').forEach((form) => form.addEventListener('submit', async (e) => {
  if (e.submitter?.value === 'cancel') return
  e.preventDefault()
  const error = $('.form-error', form)
  const button = e.submitter
  error.textContent = ''
  const f = new FormData(form)

  try {
    button.disabled = true
    if (form.dataset.action === 'open') {
      const name = f.get('name').trim()
      if (!name) return (error.textContent = 'Give the account a name.')
      const { data } = await api('/api/accounts', { method: 'POST', body: { name } })
      state.selected = data.id
      notify('good', `Opened "${data.name}".`)
    }

    if (form.dataset.action === 'deposit') {
      const amount = f.get('amount').trim()
      if (!AMOUNT.test(amount) || Number(amount) <= 0) return (error.textContent = 'Enter an amount like 500.00.')
      const req = { path: `/api/accounts/${f.get('accountId')}/deposits`, body: { amount }, key: crypto.randomUUID() }
      const { data } = await api(req.path, { method: 'POST', body: req.body, key: req.key })
      state.lastRequest = req
      reportTransfer(data, 'Added')
    }

    if (form.dataset.action === 'send') {
      const amount = f.get('amount').trim()
      const toAccountId = f.get('toChoice') === 'other' ? f.get('toOther').trim() : f.get('toChoice')
      if (!AMOUNT.test(amount) || Number(amount) <= 0) return (error.textContent = 'Enter an amount like 120.50.')
      if (!/^[0-9a-f-]{36}$/i.test(toAccountId)) return (error.textContent = 'Paste a full account ID (36 characters).')
      const body = { fromAccountId: f.get('fromAccountId'), toAccountId, amount, memo: f.get('memo').trim() || null }
      const req = { path: '/api/transfers', body, key: crypto.randomUUID() }
      const { data } = await api(req.path, { method: 'POST', body, key: req.key })
      state.lastRequest = req
      reportTransfer(data, 'Sent')
    }

    form.closest('dialog').close()
    await refresh()
  } catch (err) {
    error.textContent = err.message
  } finally {
    button.disabled = false
  }
}))

// ── One-click demo ─────────────────────────────────────────────────
// For visitors (like someone reviewing the GitHub repo) who shouldn't have to
// sign up: registers a throwaway user with a random email and password, then
// fills it with sample data through the same public API anyone could call.
// Everyone created by one demo shares a sandbox id, so its analyst only sees this demo's payments
// (other visitors trying the demo at the same time stay separate).
const sandbox = {
  get() { try { return sessionStorage.getItem(SANDBOX_KEY) || state.sandbox } catch { return state.sandbox } },
  start() {
    state.sandbox = crypto.randomUUID()
    try { sessionStorage.setItem(SANDBOX_KEY, state.sandbox) } catch {}
    return state.sandbox
  },
}

// A random UUID (36 characters) is a strong throwaway password and fits BCrypt's 72-byte limit.
async function registerDemoUser(fullName) {
  return (await api('/api/auth/register', {
    method: 'POST',
    as: '',
    body: {
      email: `demo-${crypto.randomUUID()}@demo.ledgerguard.dev`, password: crypto.randomUUID(), fullName, sandboxId: sandbox.get(),
    },
  })).data
}
const postAs = (as, path, body, key) => api(path, { method: 'POST', body, as, key: key && crypto.randomUUID() })

/** Registers a demo person with a Chequing account holding some test money. */
async function createDemoPerson(name, startingMoney) {
  const { accessToken } = await registerDemoUser(name)
  const chequing = (await postAs(accessToken, '/api/accounts', { name: 'Chequing' })).data
  await postAs(accessToken, `/api/accounts/${chequing.id}/deposits`, { amount: startingMoney }, true)
  return { name, token: accessToken, accountId: chequing.id, accountName: 'Chequing' }
}

async function startDemo() {
  const button = $('#demo')
  button.disabled = true
  button.textContent = 'Setting up your sandbox…'
  $('#auth-error').textContent = ''
  try {
    const post = postAs
    sandbox.start()

    // A second demo customer, so visitors can pay another person and see who paid them.
    const alex = await createDemoPerson('Alex Chen', '500.00')

    const me = await registerDemoUser('Demo visitor')
    store.set(me.accessToken)
    state.user = me.user

    const chequing = (await post(me.accessToken, '/api/accounts', { name: 'Chequing' })).data
    const savings = (await post(me.accessToken, '/api/accounts', { name: 'Savings' })).data
    await post(me.accessToken, `/api/accounts/${chequing.id}/deposits`, { amount: '1000.00' }, true)
    const analyst = (await api('/api/sandbox/analysts', {
      method: 'POST', as: '', body: { fullName: 'Sam Rivera', sandboxId: sandbox.get() },
    })).data
    contacts.save([
      { name: 'Demo visitor', token: me.accessToken, accountId: chequing.id, accountName: 'Chequing' },
      alex,
      { name: 'Sam Rivera', token: analyst.accessToken, role: 'ANALYST' },
    ])
    const move = (amount, memo) => post(me.accessToken, '/api/transfers',
      { fromAccountId: chequing.id, toAccountId: savings.id, amount, memo }, true)
    await move('250.00', 'Emergency fund')
    await post(alex.token, '/api/transfers',
      { fromAccountId: alex.accountId, toAccountId: chequing.id, amount: '60.00', memo: 'Concert tickets' }, true)
    await move('5000.00', 'Too much on purpose') // rejected: shows how a failed transfer is recorded
    // Large and to someone new: the fraud rules hold it for Sam to review.
    await post(me.accessToken, '/api/transfers',
      { fromAccountId: chequing.id, toAccountId: alex.accountId, amount: '600.00', memo: 'New laptop' }, true)

    state.selected = chequing.id
    await showApp()
    notify('info', 'Your private demo sandbox. Your $600 payment to Alex was held for fraud review: switch to Sam Rivera (fraud analyst) below to approve or block it.')
  } catch (err) {
    $('#auth-error').textContent = `Couldn't start the demo: ${err.message}`
  } finally {
    button.disabled = false
    button.textContent = 'Try the demo, no sign-up'
  }
}
$('#demo').addEventListener('click', startDemo)

// ── Start ──────────────────────────────────────────────────────────
// /?demo opens straight into a fresh demo (handy as a link in the README).
;(async () => {
  // /?demo=analyst opens the same demo already switched to the fraud analyst.
  const demo = new URLSearchParams(location.search).get('demo')
  if (demo !== null) {
    history.replaceState(null, '', '/')
    signOut()
    await startDemo()
    const analyst = contacts.list().find((p) => p.role === 'ANALYST')
    if (demo === 'analyst' && analyst) await switchTo(analyst)
    return
  }
  if (!store.get()) return signOut()
  try { await showApp() } catch { signOut() }
})()
