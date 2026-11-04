// BudgetMe backend (Cloudflare Worker): the only place that holds Plaid's secret and users' Plaid access tokens.
// Stores per user: their Plaid items (access token encrypted). Never stores transactions: the phone keeps its own sync
// cursors, sends them with /sync, and applies the changes on the device.
// Sign-in: the app sends a Google access token; Google's tokeninfo says who it is and which app asked for it.
//
//   POST   /link-token          -> { link_token }                 start Plaid Link on the phone
//   POST   /items               { public_token, institution }     finish Link: exchange and store
//   GET    /items               -> [{ id, institution }]
//   DELETE /items/:id                                             disconnect at Plaid, forget the token
//   POST   /sync                { cursors: { itemId: cursor } } -> [{ id, institution, accounts, added, modified, removed, cursor, error }]
//                                accounts carry Plaid's balances (available, current, balanceAt)
//   POST   /refresh                                               ask Plaid to check the banks now (results arrive via /sync)
//   POST   /transactions        { start, end } -> same shape as /sync, every transaction dated start..end in `added`
//                                (the phone's "what's different" check; the sync cursors aren't touched)
//
// ponytail: no webhooks yet (the phone syncs every 15 min and after alerts); KV is fine for one row per user.

const PLAID = { sandbox: "https://sandbox.plaid.com", production: "https://production.plaid.com" };

export default {
  async fetch(req, env) {
    try {
      const user = await signedIn(req, env);
      if (!user) return json({ error: "sign in with Google" }, 401);
      const url = new URL(req.url), path = url.pathname, m = req.method;
      if (m === "POST" && path === "/link-token") return json(await linkToken(env, user));
      if (m === "POST" && path === "/items") return json(await addItem(env, user, await req.json()));
      if (m === "GET" && path === "/items") return json((await load(env, user)).map(({ id, institution }) => ({ id, institution })));
      if (m === "DELETE" && path.startsWith("/items/")) return json(await removeItem(env, user, path.slice(7)));
      if (m === "POST" && path === "/sync") return json(await sync(env, user, (await req.json()).cursors ?? {}));
      if (m === "POST" && path === "/refresh") return json(await refresh(env, user));
      if (m === "POST" && path === "/transactions") { const b = await req.json(); return json(await range(env, user, b.start, b.end)); }
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: e.message, code: e.code }, e.status ?? 500);
    }
  },
};

// ---------- sign-in ----------

async function signedIn(req, env) {
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer /, "");
  if (!token) return null;
  const r = await fetch("https://oauth2.googleapis.com/tokeninfo?access_token=" + encodeURIComponent(token));
  if (!r.ok) return null;
  const info = await r.json();
  const allowed = (env.GOOGLE_CLIENT_IDS ?? "").split(",").map(s => s.trim()).filter(Boolean);
  if (!allowed.includes(info.azp ?? info.aud)) throw Object.assign(new Error(`unknown app ${info.azp ?? info.aud}`), { status: 403 });
  return info.sub ?? null;  // Google's stable user id; needs the openid scope
}

// ---------- Plaid ----------

async function plaid(env, path, body) {
  const r = await fetch(PLAID[env.PLAID_ENV] + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ client_id: env.PLAID_CLIENT_ID, secret: env.PLAID_SECRET, ...body }),
  });
  const data = await r.json();
  if (!r.ok) throw Object.assign(new Error(data.error_message ?? `Plaid ${r.status}`), { status: 502, code: data.error_code });
  return data;
}

const linkToken = async (env, user) => ({
  link_token: (await plaid(env, "/link/token/create", {
    client_name: "BudgetMe", language: "en", country_codes: ["US"], user: { client_user_id: user },
    products: ["transactions"], transactions: { days_requested: 30 }, android_package_name: env.ANDROID_PACKAGE,
  })).link_token,
});

async function addItem(env, user, { public_token, institution }) {
  const { access_token, item_id } = await plaid(env, "/item/public_token/exchange", { public_token });
  const items = (await load(env, user)).filter(i => i.id !== item_id);
  items.push({ id: item_id, institution: institution ?? "Bank", token: access_token });
  await save(env, user, items);
  return { id: item_id };
}

async function removeItem(env, user, id) {
  const items = await load(env, user), item = items.find(i => i.id === id);
  if (!item) return { removed: false };
  await plaid(env, "/item/remove", { access_token: item.token }).catch(() => {});  // already gone at Plaid: still forget it
  await save(env, user, items.filter(i => i !== item));
  return { removed: true };
}

async function sync(env, user, cursors) {
  const out = [];
  for (const item of await load(env, user)) {
    const start = cursors[item.id] ?? null;
    try {
      const r = await pull(env, item.token, start);
      // /transactions/sync only lists accounts that had transactions in this batch; /accounts/get has every account's
      // balance as Plaid last read it (free; Check the bank now refreshes it)
      r.accounts = (await plaid(env, "/accounts/get", { access_token: item.token })).accounts.map(account);
      out.push({ id: item.id, institution: item.institution, ...r });
    } catch (e) {
      out.push({ id: item.id, institution: item.institution, error: e.code ?? e.message });  // e.g. ITEM_LOGIN_REQUIRED
    }
  }
  return out;
}

// All pages since the cursor. If the data changes mid-pagination, Plaid asks us to restart from the original cursor.
async function pull(env, access_token, start) {
  for (let attempt = 0; attempt < 3; attempt++) {
    let cursor = start, added = [], modified = [], removed = [], accounts = [];
    try {
      for (let page = 0; page < 50; page++) {
        const r = await plaid(env, "/transactions/sync", { access_token, count: 500, options: { include_original_description: true },
          ...(cursor ? { cursor } : {}) });
        added.push(...r.added.map(slim)); modified.push(...r.modified.map(slim)); removed.push(...r.removed.map(t => t.transaction_id));
        accounts = r.accounts.map(account);
        cursor = r.next_cursor;
        if (!r.has_more) return { accounts, added, modified, removed, cursor };
      }
      throw new Error("too many pages");
    } catch (e) {
      if (e.code !== "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION") throw e;
    }
  }
  throw new Error("transactions kept changing; try again");
}

// Balances as Plaid last read them from the bank (passed through, not stored): available counts pending charges.
const account = a => ({ id: a.account_id, type: a.type, subtype: a.subtype, name: a.name, mask: a.mask,
  available: a.balances?.available ?? null, current: a.balances?.current ?? null, balanceAt: a.balances?.last_updated_datetime ?? null });

// Only what the phone uses. amount > 0 = money out (Plaid's convention).
const slim = t => ({
  id: t.transaction_id, account: t.account_id, amount: t.amount, date: t.authorized_date ?? t.date,
  merchant: t.merchant_name ?? t.name, raw: t.original_description ?? t.name, pending: t.pending,
  replaces: t.pending_transaction_id, category: t.personal_finance_category?.primary ?? null,
});

const DAY = /^\d{4}-\d{2}-\d{2}$/;

// One date range of every connected bank, for the phone to compare with its own records. Nothing is stored.
async function range(env, user, start, end) {
  if (!DAY.test(start ?? "") || !DAY.test(end ?? "")) throw Object.assign(new Error("start and end: YYYY-MM-DD"), { status: 400 });
  const out = [];
  for (const item of await load(env, user)) {
    try {
      let txns = [], accounts = [], total = Infinity;
      while (txns.length < total && txns.length < 5000) {
        const r = await plaid(env, "/transactions/get", { access_token: item.token, start_date: start, end_date: end,
          options: { count: 500, offset: txns.length, include_original_description: true } });
        txns.push(...r.transactions.map(slim)); accounts = r.accounts.map(account); total = r.total_transactions;
        if (r.transactions.length === 0) break;
      }
      out.push({ id: item.id, institution: item.institution, accounts, added: txns, modified: [], removed: [], cursor: "" });
    } catch (e) {
      out.push({ id: item.id, institution: item.institution, error: e.code ?? e.message });
    }
  }
  return out;
}

async function refresh(env, user) {
  const items = await load(env, user);
  const results = await Promise.allSettled(items.map(i => plaid(env, "/transactions/refresh", { access_token: i.token })));
  return { refreshed: results.filter(r => r.status === "fulfilled").length, of: items.length };
}

// ---------- storage: one KV row per user, access tokens encrypted (AES-GCM, key in the ENC_KEY secret) ----------

async function load(env, user) {
  const raw = await env.USERS.get("u:" + user);
  if (!raw) return [];
  return Promise.all(JSON.parse(raw).map(async i => ({ ...i, token: await decrypt(env, i.token) })));
}

async function save(env, user, items) {
  const rows = await Promise.all(items.map(async i => ({ ...i, token: await encrypt(env, i.token) })));
  await env.USERS.put("u:" + user, JSON.stringify(rows));
}

const key = env => crypto.subtle.importKey("raw", Uint8Array.from(atob(env.ENC_KEY), c => c.charCodeAt(0)), "AES-GCM", false, ["encrypt", "decrypt"]);
const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));

async function encrypt(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await key(env), new TextEncoder().encode(text));
  return b64(iv) + "." + b64(ct);
}

async function decrypt(env, stored) {
  const [iv, ct] = stored.split(".").map(s => Uint8Array.from(atob(s), c => c.charCodeAt(0)));
  return new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv }, await key(env), ct));
}

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
