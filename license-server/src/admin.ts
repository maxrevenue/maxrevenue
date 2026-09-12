/** Operator dashboard HTML for GET /admin (no framework). */
export function adminPage(): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>Roatz Admin</title>
<style>
  :root { --bg:#14161a; --card:#1e2126; --fg:#f0f2f5; --muted:#969ba5; --gold:#ffc33c; --green:#41c35f; --red:#eb4b4b; --border:#3c414b; }
  * { box-sizing: border-box; }
  body { margin:0; font:15px/1.45 system-ui,Segoe UI,sans-serif; background:var(--bg); color:var(--fg); }
  main { max-width:520px; margin:40px auto; padding:0 16px; }
  h1 { color:var(--gold); font-size:1.5rem; margin:0 0 4px; }
  .sub { color:var(--muted); margin:0 0 24px; font-size:.9rem; }
  section { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:18px; margin-bottom:16px; }
  label { display:block; color:var(--muted); font-size:.8rem; margin:10px 0 4px; }
  input, textarea { width:100%; padding:10px 12px; border-radius:8px; border:1px solid var(--border); background:#121418; color:var(--fg); font:inherit; }
  textarea { min-height:64px; resize:vertical; }
  .row { display:flex; gap:10px; align-items:end; }
  .row > * { flex:1; }
  button { margin-top:14px; width:100%; padding:12px; border:0; border-radius:8px; font:600 15px inherit; cursor:pointer; }
  .primary { background:var(--gold); color:#1c1e22; }
  .danger { background:var(--red); color:#fff; }
  .ghost { background:transparent; color:var(--muted); border:1px solid var(--border); }
  #out { white-space:pre-wrap; word-break:break-all; font-family:ui-monospace,Consolas,monospace; font-size:.9rem; min-height:2.5em; }
  #out.ok { color:var(--green); }
  #out.err { color:var(--red); }
  .keybox { font-size:1.25rem; font-weight:700; letter-spacing:.04em; color:var(--gold); margin-top:8px; }
  .hint { color:var(--muted); font-size:.8rem; margin-top:8px; }
</style>
</head>
<body>
<main>
  <h1>Roatz Admin</h1>
  <p class="sub">Issue and revoke license keys. Paste your admin secret once — it stays in this browser only.</p>

  <section>
    <label>Admin secret</label>
    <input id="secret" type="password" autocomplete="off" placeholder="ADMIN_SECRET"/>
    <button class="ghost" type="button" id="saveSecret">Remember in this browser</button>
  </section>

  <section>
    <h2 style="margin:0;font-size:1.05rem">Issue key</h2>
    <label>Note (Discord / buyer)</label>
    <input id="note" placeholder="discord:user#1234"/>
    <div class="row">
      <div>
        <label>Days</label>
        <input id="days" type="number" min="0" max="3650" value="30"/>
      </div>
      <div>
        <label>Hours</label>
        <input id="hours" type="number" min="0" max="24" value="0"/>
      </div>
    </div>
    <p class="hint">Days and hours add. Leave both at 0 for a perpetual key. A 24-hour trial is Days 0 + Hours 24.</p>
    <button class="primary" type="button" id="issue">Issue key</button>
  </section>

  <section>
    <h2 style="margin:0;font-size:1.05rem">Revoke key</h2>
    <label>Key</label>
    <input id="revokeKey" placeholder="RZ-XXXX-XXXX-XXXX"/>
    <button class="danger" type="button" id="revoke">Revoke</button>
  </section>

  <section>
    <h2 style="margin:0;font-size:1.05rem">Result</h2>
    <div id="out"></div>
    <div class="keybox" id="keyOut"></div>
    <p class="hint">Copy the key into a Discord DM. Buyer activates it in Roatz → Activate.</p>
  </section>
</main>
<script>
const secretEl = document.getElementById('secret');
const out = document.getElementById('out');
const keyOut = document.getElementById('keyOut');
secretEl.value = sessionStorage.getItem('roatzAdmin') || '';

document.getElementById('saveSecret').onclick = () => {
  sessionStorage.setItem('roatzAdmin', secretEl.value.trim());
  show('ok', 'Admin secret saved for this browser tab session.');
};

async function api(path, body) {
  const secret = secretEl.value.trim();
  if (!secret) throw new Error('Paste the admin secret first.');
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Roatz-Admin': secret },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.ok) {
    throw new Error(data.error || ('HTTP ' + res.status));
  }
  return data;
}

function show(kind, msg, key) {
  out.className = kind;
  out.textContent = msg;
  keyOut.textContent = key || '';
}

document.getElementById('issue').onclick = async () => {
  try {
    const days = Number(document.getElementById('days').value || 0);
    const hours = Number(document.getElementById('hours').value || 0);
    const note = document.getElementById('note').value.trim();
    const data = await api('/v1/issue', { note, days, hours });
    show('ok', durationLabel(data.hours) + ' (countdown starts on first activate):', data.key);
  } catch (e) {
    show('err', String(e.message || e));
  }
};

/** "Issued 24-hour key" / "Issued 1-day-12-hour key" / "Issued perpetual key". */
function durationLabel(hours) {
  const h = Number(hours || 0);
  if (!(h > 0)) return 'Issued perpetual key:';
  const d = Math.floor(h / 24);
  const r = h % 24;
  const parts = [];
  if (d > 0) parts.push(d + '-day');
  if (r > 0) parts.push(r + '-hour');
  return 'Issued ' + parts.join('-') + ' key:';
}

document.getElementById('revoke').onclick = async () => {
  try {
    const key = document.getElementById('revokeKey').value.trim().toUpperCase();
    const data = await api('/v1/revoke', { key });
    show('ok', 'Revoked ' + data.key, '');
  } catch (e) {
    show('err', String(e.message || e));
  }
};
</script>
</body>
</html>`;
}
