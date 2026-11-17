// POST /categorize: Claude's guess for a purchase from a merchant the app has never seen.
// The phone sends structured data only (its categories, example merchants, the purchase); the prompt is built here from
// a fixed template, so a signed-in user can get a purchase categorized and nothing else with the server's key. Nothing
// is stored or logged. Same template as engine/llm_tier.py (the reference) and the app's old on-device call.
import Anthropic from "@anthropic-ai/sdk";

const TEMPLATE = `You categorize one personal purchase at a time for a budgeting app. The user will see your top choices as buttons on a phone notification and tap one, so a good ranking matters more than a single answer.

The categories are the user's own, defined in their words below. Follow their definitions, not general conventions. For example, if they file restaurants under "Fun", do that, even though many budgets would call it "Food".

<categories>
{categories}
</categories>

<how_this_user_files_things>
Merchants this user has filed most often in each category. Use them to calibrate what each category means to this person.
{examples}
</how_this_user_files_things>

How to decide:
1. First work out what kind of business the merchant is. Bank descriptors are often truncated or prefixed by payment processors (SQ *, TST*, SP *, PAYPAL *, etc.). Strip those and use your knowledge of real businesses.
2. Then apply the category definitions, including any timing or amount rules in them (weekday vs. weekend, dollar thresholds).
3. {fallback_rule}
4. If the purchase is a person-to-person payment (Venmo, Zelle, Cash App) and a note is given, the note describes what was bought; categorize by the note.

Give merchant_type (a few words: what this business is), a ranking of every eligible category above with probabilities that sum to about 1, and reason (one short sentence).`;

// The reply's shape, enforced by structured outputs.
const SCHEMA = {
  type: "object",
  properties: {
    merchant_type: { type: "string" },
    ranking: { type: "array", items: { type: "object", properties: { category: { type: "string" }, probability: { type: "number" } },
      required: ["category", "probability"], additionalProperties: false } },
    reason: { type: "string" },
  },
  required: ["merchant_type", "ranking", "reason"],
  additionalProperties: false,
};

const bad = msg => Object.assign(new Error(msg), { status: 400 });
const text = (v, max, what) => {
  if (typeof v !== "string" || !v.trim() || v.length > max) throw bad(`${what}: text up to ${max} characters`);
  return v.trim();
};
const optText = (v, max, what) => (v == null || v === "" ? null : text(v, max, what));
const list = (v, max, what) => {
  if (v == null) return [];
  if (!Array.isArray(v) || v.length > max) throw bad(`${what}: up to ${max} items`);
  return v;
};
const DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

/** Checks the phone's request and builds the prompt from it. Throws a 400 for anything outside the limits. */
export function categorizePrompt(body) {
  const p = body?.prompt ?? {}, buy = body?.purchase ?? {};
  const cats = list(p.categories, 30, "categories").map(c => ({
    name: text(c?.name, 60, "category name"), description: text(c?.description, 800, "category description"), fallback: c?.fallback === true,
  }));
  if (!cats.length) throw bad("categories: at least one");
  const examples = list(p.examples, 30, "examples").map(e => ({
    category: text(e?.category, 60, "example category"), merchants: list(e?.merchants, 10, "example merchants").map(m => text(m, 60, "merchant")),
  })).filter(e => e.merchants.length);
  const split = list(p.split, 8, "split").map(m => text(m, 60, "merchant"));

  const merchant = text(buy.merchant, 120, "merchant");
  if (!Number.isInteger(buy.cents) || Math.abs(buy.cents) > 100_000_000) throw bad("cents: a whole number");
  if (typeof buy.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(buy.date)) throw bad("date: yyyy-mm-dd");
  const account = optText(buy.account, 60, "account"), note = optText(buy.note, 200, "note");

  const exLines = examples.map(e => `- ${e.category}: ${e.merchants.join(", ")}`);
  if (split.length) exLines.push(`- Split across categories depending on the occasion: ${split.join(", ")}`);
  if (!exLines.length) exLines.push("(No history yet. Rely on the category definitions.)");
  const fb = cats.find(c => c.fallback)?.name;
  const fbRule = fb ? `"${fb}" is the user's bucket for things that don't fit elsewhere, such as subscriptions, services, ` +
    "appointments, and one-off purchases. Rank it first only when no other category plausibly fits, but always include it in the ranking."
    : "Always rank every eligible category.";
  const system = TEMPLATE
    .replace("{categories}", cats.map(c => `- ${c.name}${c.fallback ? " (fallback: only when nothing else fits)" : ""}: ${c.description}`).join("\n"))
    .replace("{examples}", exLines.join("\n"))
    .replace("{fallback_rule}", fbRule);

  const amount = buy.cents === 0 ? "Amount: unknown" : `Amount: $${(Math.abs(buy.cents) / 100).toFixed(2)}${buy.cents < 0 ? " (refund)" : ""}`;
  const user = [`Merchant: ${merchant}`, amount, `Date: ${buy.date} (${DAYS[new Date(buy.date + "T12:00:00Z").getUTCDay()]})`,
    account && `Account: ${account}`, note && `Payment note: ${note}`].filter(Boolean).join("\n");
  return { system, user };
}

/** Per-user daily cap, counted in KV (ponytail: KV is eventually consistent, so the cap is approximate; fine as a cost guard). */
async function underCap(env, user) {
  const limit = Number(env.LLM_DAILY_LIMIT ?? 200);
  const k = `llm:${user}:${new Date().toISOString().slice(0, 10)}`;
  const used = Number((await env.USERS.get(k)) ?? 0);
  if (used >= limit) return false;
  await env.USERS.put(k, String(used + 1), { expirationTtl: 2 * 86400 });
  return true;
}

export async function categorize(env, user, body) {
  const { system, user: purchase } = categorizePrompt(body);
  if (!(await underCap(env, user))) throw Object.assign(new Error("daily limit reached"), { status: 429 });
  const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, maxRetries: 1, timeout: 20_000 });
  let res;
  try {
    res = await client.messages.create({
      model: env.LLM_MODEL ?? "claude-haiku-5-5",
      max_tokens: 1024,  // room for a short think before the JSON
      system: [{ type: "text", text: system, cache_control: { type: "ephemeral" } }],
      messages: [{ role: "user", content: purchase }],
      output_config: { effort: "low", format: { type: "json_schema", schema: SCHEMA } },
    });
  } catch (e) {
    if (e instanceof Anthropic.RateLimitError) throw Object.assign(new Error("Claude is busy, try again shortly"), { status: 429 });
    if (e instanceof Anthropic.APIError) throw Object.assign(new Error(`Claude error ${e.status}`), { status: 502 });
    throw e;
  }
  if (res.stop_reason === "refusal") throw Object.assign(new Error("Claude declined this one"), { status: 422 });
  if (res.stop_reason === "max_tokens") throw Object.assign(new Error("Claude's answer was cut off"), { status: 502 });
  const out = res.content.filter(b => b.type === "text").map(b => b.text).join("");
  return JSON.parse(out);
}
