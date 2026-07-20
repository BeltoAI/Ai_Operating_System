#!/usr/bin/env bash
# pull_brain_stats.sh — dump SlyOS BRAIN + LM stats from the connected (debug) phone into a clean, paste-able
# report. API keys/tokens are NEVER printed (only SET / empty), so the output is safe to share.
#   chmod +x pull_brain_stats.sh && ./pull_brain_stats.sh
set -uo pipefail
PKG="com.agentos.shell"

command -v adb >/dev/null 2>&1 || { echo "adb not found — install platform-tools."; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No device. Plug in the phone + enable USB debugging."; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 not found."; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

for f in slyos slyos_cost slyos_health slyos_freetier slyos_provider_limit slyos_analytics slyos_vec_meta; do
  adb exec-out run-as "$PKG" cat "shared_prefs/$f.xml" 2>/dev/null > "$TMP/$f.xml" || true
done
for db in slyos_vec slyos_msgs slyos_conn slyos_leads slyos_expenses slyos_photos slyos_staff slyos_doctext slyos_agent_kb outreach; do
  for ext in "" "-wal" "-shm"; do
    adb exec-out run-as "$PKG" cat "databases/$db.db$ext" 2>/dev/null > "$TMP/$db.db$ext" || true
  done
done
ODE="$(adb exec-out run-as "$PKG" sh -c 'ls -l files/models/use_embedder.tflite 2>/dev/null' 2>/dev/null || true)"

python3 - "$TMP" "$ODE" <<'PY'
import sys, os, time, sqlite3, datetime, xml.etree.ElementTree as ET
tmp, ode = sys.argv[1], sys.argv[2]
def load(name):
    p=os.path.join(tmp,name+'.xml'); d={}
    if os.path.exists(p):
        try:
            for e in ET.parse(p).getroot():
                d[e.get('name')] = e.get('value') if e.get('value') is not None else (e.text or '')
        except Exception: pass
    return d
S=load('slyos'); COST=load('slyos_cost'); H=load('slyos_health')
FT=load('slyos_freetier'); PL=load('slyos_provider_limit'); AN=load('slyos_analytics')
now=int(time.time()*1000); today=datetime.date.today()
DK=f"{today.year}-{today.timetuple().tm_yday}"       # HealthStore / FreeTierMeter (YEAR-DAY_OF_YEAR)
MK=f"{today.year}-{today.month-1}"                    # CostStore month (0-indexed)
PROV=['anthropic','openai','gemini','groq','cerebras','mistral','githubmodels']
CAP={'gemini':1500,'groq':1000,'cerebras':14400,'nvidia':1000,'openrouter':50,'mistral':None,'githubmodels':None}

print("="*54); print("  SLYOS — BRAIN + LM STATS   "+str(today)); print("="*54)

emb=pen=None
try:
    con=sqlite3.connect(os.path.join(tmp,'slyos_vec.db'))
    emb=con.execute("select count(*) from vmem where v is not null").fetchone()[0]
    pen=con.execute("select count(*) from vmem where v is null").fetchone()[0]
except Exception: pass
print("\n[ SEMANTIC MEMORY ]")
print(f"  embed setting     : {S.get('embed_provider','auto')}")
print(f"  on-device embedder: {'PRESENT' if ode.strip() else 'not downloaded'}")
print(f"  indexed / pending : {emb} / {pen}")

print("\n[ REQUESTS PER AI — today ]")
hit=False
for p in PROV:
    ok=H.get(f'ok_{DK}_{p}','0'); fail=H.get(f'fail_{DK}_{p}','0'); err=H.get(f'lasterr_{p}','')
    if ok!='0' or fail!='0':
        hit=True; print(f"  {p:12} ok={ok:>4} fail={fail:>3}"+(f"  last_err={err[:56]}" if fail!='0' and err else ""))
if not hit: print("  (no model calls recorded today)")

print("\n[ FREE-TIER USED — today ]")
hit=False
for p in PROV:
    used=FT.get(f'{DK}_{p}'); parked=int(PL.get(f'until_{p}','0') or 0)>now
    if used or parked:
        hit=True; cap=CAP.get(p)
        print(f"  {p:12} {used or 0}"+(f"/{cap}" if cap else "")+("   *** PARKED ***" if parked else ""))
if not hit: print("  (no free-tier usage today)")
parked=[p for p in PROV if int(PL.get(f'until_{p}','0') or 0)>now]
print(f"  parked now: {', '.join(parked) if parked else 'none'}")

print("\n[ COST / USAGE ]")
print(f"  this month : ${int(COST.get('cost_'+MK,'0') or 0)/1e6:.4f}  calls={COST.get('calls_'+MK,'0')}  tokens={COST.get('tok_'+MK,'0')}")
print(f"  lifetime   : calls={COST.get('life_calls','0')}  tokens={COST.get('life_tok','0')}")

print("\n[ ROUTING / CONFIG ]")
print(f"  preferred     : {S.get('pref_provider','') or '(auto)'}")
print(f"  monthly budget: ${S.get('monthly_budget','0')}")
for k in sorted(S):
    if k.startswith('tier_prov_') or k.startswith('model_'): print(f"    {k} = {S[k]}")

print("\n[ KEYS PRESENT — values hidden ]")
for k in sorted(S):
    if k.endswith('_key') or 'token' in k:
        print(f"  {k:22}: {'SET' if (S.get(k) or '').strip() else '(empty)'}")

print("\n[ ANALYTICS ]")
print(f"  enabled: {AN.get('enabled','true')}  device: {(AN.get('did','') or '?')[:8]}…")
print("\n(keys redacted — safe to paste)")
PY
