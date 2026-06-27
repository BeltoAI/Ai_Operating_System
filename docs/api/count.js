// Live download counter — ticks instantly on each download click (no GitHub lag).
// Storage: Vercel KV / Upstash Redis. Provision it in Vercel → Storage (one click); it injects the
// KV_REST_API_URL + KV_REST_API_TOKEN env vars used below. Until then this returns {downloads:null}
// and the site falls back to the GitHub count, so the page never breaks.
//
//   GET  /api/count  -> { downloads: <number> }
//   POST /api/count  -> increments, returns { downloads: <number> }
module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  if (req.method === 'OPTIONS') { res.status(204).end(); return; }

  const url = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || '';
  const token = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || '';
  if (!url || !token) { res.status(200).json({ downloads: null, error: 'no-store' }); return; }

  const key = 'slyos:downloads';
  const path = req.method === 'POST' ? `incr/${key}` : `get/${key}`;
  try {
    const r = await fetch(`${url}/${path}`, { headers: { Authorization: `Bearer ${token}` } });
    const j = await r.json();
    const val = parseInt(j && j.result, 10) || 0;
    res.status(200).json({ downloads: val });
  } catch (e) {
    res.status(200).json({ downloads: null });
  }
};
