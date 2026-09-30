import pandas as pd, datetime as dt
from collections import Counter, defaultdict
import predictor as P, os
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
df=pd.read_csv(os.path.join(ROOT,'data','labeled_transactions.csv'), parse_dates=['date']).assign(date=lambda x: x.date.dt.date.where(x.date.notna(), None), flags=lambda x: x['flags'].fillna(''))
v=df[(df.kind=='variable')&(~df['flags'].str.contains('placeholder'))].copy()
def eff_date(r):
    y,m=map(int,r.month.split('-'))
    if r.date is not None and not pd.isna(r.date) and r.date.strftime('%Y-%m')==r.month: return r.date
    return dt.date(y,m,15)
v['d']=v.apply(eff_date,axis=1); v['dated']=v.apply(lambda r: r.date is not None and not pd.isna(r.date) and r.date.strftime('%Y-%m')==r.month,axis=1)
v=v.sort_values(['d','source'])
TEST_FROM='2026-04'
train=v[v.month<TEST_FROM]; test=v[(v.month>=TEST_FROM)&(v.month<='2026-09')]
def run(online):
    m=P.Predictor()
    for r in train.itertuples(): m.learn(r.merchant,r.amount,r.d,r.category)
    out=[]
    for r in test.itertuples():
        p=m.predict(r.merchant,r.amount,r.d)
        out.append(dict(actual=r.category,b=p['buttons'],conf=p['confidence'],tier=p['tier'].split(' (')[0] if 'history' in p['tier'] else p['tier'],
                        tier_full=p['tier'],dated=r.dated,d=r.d,fw=r.fun_week,month=r.month,merchant=r.merchant,amount=r.amount))
        if online: m.learn(r.merchant,r.amount,r.d,r.category)
    o=pd.DataFrame(out); o['top1']=o.apply(lambda x:x.b[0]==x.actual,axis=1); o['top2']=o.apply(lambda x:x.actual in x.b[:2],axis=1); o['top3']=o.apply(lambda x:x.actual in x.b,axis=1)
    return o
res={}
for online in [False,True]:
    o=run(online); res[online]=o
    print('\n=== learning during test:',online,'| n =',len(o))
    print(o[['top1','top2','top3']].mean().round(3).to_dict())
    print(o.groupby('tier_full')[['top1','top3']].mean().round(3).join(o.groupby('tier_full').size().rename('n')))
    print(o.groupby('actual')[['top1','top3']].mean().round(3).join(o.groupby('actual').size().rename('n')))
o=res[True]
print('\nconfidence bands (online):')
o['band']=pd.cut(o.conf,[0,.5,.7,.85,.95,1.01],right=False)
print(o.groupby('band',observed=True).agg(n=('top1','size'),share=('top1',lambda s:len(s)/len(o)),top1=('top1','mean')).round(3))
# Fun week
f=o[(o.actual=='Fun')&o.dated]
def wk(x):
    opts=P.fun_week_options(x.d); actual=(x.month,int(x.fw))
    return pd.Series(dict(in_opts=actual in opts,ambig=len(opts)>1,first=opts[-1]==actual))
w=f.apply(wk,axis=1)
print('\nFun week (dated Fun, test period): n',len(w),'| correct week offered',round(w.in_opts.mean(),3),'| two-button share',round(w.ambig.mean(),3),'| single-week & correct',round((~w.ambig & w.in_opts).mean(),3))
print(f[~w.in_opts][['d','merchant','month','fw']].assign(opts=lambda x:x.d.map(P.fun_week_options)).to_string())
misses=o[~o.top3]
print('\nmisses (not in 3 buttons):'); print(misses[['d','merchant','amount','actual','b','tier_full']].to_string())
o.assign(buttons=o.b.map(' | '.join)).drop(columns=['b']).to_csv(os.path.join(ROOT,'engine','backtest_results.csv'),index=False)
