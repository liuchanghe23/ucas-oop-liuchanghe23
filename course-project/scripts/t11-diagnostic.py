"""Run a bounded real T11 diagnostic through the fault scheduler in the prepared oop-t11 lab."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import uuid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--lab-dir',type=Path,default=Path(__file__).resolve().parents[1]/'.local/t11-tools')
root=parser.parse_args().lab_dir.resolve()
output=root/'runs'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
output.mkdir(parents=True)

def save(name,value):
    (output/name).write_text(json.dumps(value,indent=2)+'\n')

def command(*args, timeout=20):
    p=subprocess.run(args,capture_output=True,text=True,timeout=timeout)
    if p.returncode:
        raise RuntimeError(p.stderr[:2000])
    return p.stdout

def kube(*args):
    return command('k3s','kubectl','-n','oop-t11',*args)

def api(method,path,payload=None):
    data=None if payload is None else json.dumps(payload).encode()
    req=urllib.request.Request('http://127.0.0.1:8103/api/faults'+path,data=data,method=method,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=40) as response:
        result=json.load(response)
    if not result.get('success'):
        raise RuntimeError(result)
    return result['data']

node=json.loads(command('k3s','kubectl','get','node','oop-t11','-o','json'))
assert node['metadata']['name']=='oop-t11'
assert not json.loads(command('k3s','kubectl','get','chaosblades','-o','json'))['items'], 'Remove existing lab faults before running T11'
services=json.loads(kube('get','services','-o','json'))['items']
addresses={item['metadata']['name']:item['spec']['clusterIP'] for item in services}
pods=json.loads(kube('get','pods','-o','json'))['items']
by_app={item['metadata']['labels']['app']:item for item in pods}
checkout=by_app['checkout']
broker=by_app['kafka']
assert all(c.get('ready') for p in pods for c in p['status'].get('containerStatuses',[]))
save('pods-before.json',pods)
save('services.json',services)
baseline=json.loads((root/'baseline.json').read_text())
assert len(baseline['orders']) == 3 and all(order['order']['orderId'] in baseline['databaseOrderIds'] for order in baseline['orders']), 'A delivered 3/3 baseline is required'
save('baseline.json',baseline)
container_id=checkout['status']['containerStatuses'][0]['containerID'].split('://',1)[1]
info=json.loads(command('k3s','crictl','inspect',container_id))
pid=str(info['info']['pid'])

def tc():
    return {'qdisc':command('nsenter','--target',pid,'--net','tc','-s','qdisc','show','dev','eth0'),
            'filters':command('nsenter','--target',pid,'--net','tc','-s','filter','show','dev','eth0')}

tc_before=tc()
assert 'netem' not in tc_before['qdisc'] and not tc_before['filters'].strip(), 'The selected interface already has fault rules'
save('tc-before.json',tc_before)

def rpc(service,port,method,payload,trace_id):
    return json.loads(command(str(root/'grpcurl'),'-plaintext','-max-time','10','-H','traceparent: 00-'+trace_id+'-0123456789abcdef-01',
                             '-import-path',str(root/'otel-3.1.0/pb'),'-proto','demo.proto','-d',json.dumps(payload),
                             f'{addresses[service]}:{port}','oteldemo.'+method,timeout=15))

def order(index):
    trace_id=uuid.uuid4().hex
    user='t11-fault-'+uuid.uuid4().hex
    started=datetime.now(timezone.utc).isoformat()
    item={'index':index,'traceId':trace_id,'userId':user,'startedAt':started}
    try:
        rpc('cart',7070,'CartService/AddItem',{'userId':user,'item':{'productId':'L9ECAV7KIM','quantity':1}},trace_id)
        result=rpc('checkout',5050,'CheckoutService/PlaceOrder',{'userId':user,'userCurrency':'USD',
            'address':{'streetAddress':'1 Test Street','city':'Test City','state':'CA','country':'United States','zipCode':'94043'},
            'email':'t11@example.invalid','creditCard':{'creditCardNumber':'4111111111111111','creditCardCvv':123,
                                                      'creditCardExpirationYear':2030,'creditCardExpirationMonth':12}},trace_id)
        item['response']=result
    except Exception as error:
        item['error']=str(error)
    item['endedAt']=datetime.now(timezone.utc).isoformat()
    return item

name='t11-'+uuid.uuid4().hex[:12]
matchers={'names':[checkout['metadata']['name']],'namespace':['oop-t11'],'interface':['eth0'],
          'percent':['100'],'remote-port':['9092'],
          'destination-ip':[addresses['kafka'],broker['status']['podIP']],
          'use-sidecar-container-network':['true'],'timeout':['45']}
fault={'apiVersion':'chaosblade.io/v1alpha1','kind':'ChaosBlade','metadata':{'name':name},
       'spec':{'experiments':[{'scope':'pod','target':'network','action':'loss','desc':'T11 isolated checkout to Kafka diagnostic',
                              'matchers':[{'name':key,'value':value} for key,value in matchers.items()]}]}}
save('fault-request.json',fault)
summary={'scope':'REAL_ISOLATED_T11_DIAGNOSTIC','name':name,'startedAt':datetime.now(timezone.utc).isoformat(),
         'templateId':'DT-DUALWRITE-LOST-001','faultSeconds':20,'recoveryWaitSeconds':30,
         'conclusion':'INCONCLUSIVE','cleanupStatus':'NOT_STARTED',
         'limitations':['Manual diagnostic experiment; not compiled or executed by svc-defect-transfer.',
                        'Effective total publish retry budget is not yet established; this challenge does not prove exhaustion.',
                        'Reference source uses ack=0; effective runtime producer configuration and correlated publish-failure observation were not verified.',
                        'Payment is the demo simulated payment service, not an external bank ledger.',
                        'Image digests pinned; 3.1.0 source tag is a reference, not verified image build provenance.']}
created=False
try:
    created=True
    save('scheduler-create.json',api('POST','/execute?'+urllib.parse.urlencode({'name':name,'durationSec':60}),fault))
    started=time.monotonic()
    while time.monotonic()-started < 25:
        status=json.loads(command('k3s','kubectl','get','chaosblade',name,'-o','json'))
        save('fault-status.json',status)
        phase=status.get('status',{}).get('phase')
        if phase=='Running':
            experiments=status.get('status',{}).get('expStatuses',[])
            if not experiments or not all(item.get('success') is True for item in experiments):
                raise RuntimeError('Operator has not confirmed successful target injection')
            break
        if phase=='Error':
            raise RuntimeError('ChaosBlade reported Error: '+json.dumps(status.get('status')))
        time.sleep(1)
    else:
        raise RuntimeError('Fault did not become Running within 25 seconds')
    save('tc-injected.json',tc())
    fault_started=time.monotonic()
    orders=[]
    for index in range(5):
        item=order(index)
        orders.append(item)
        save('fault-orders.json',orders)
        print(json.dumps({'index':index,'orderId':item.get('response',{}).get('order',{}).get('orderId'),'error':item.get('error')}),flush=True)
        if time.monotonic()-fault_started>18:
            break
        time.sleep(1)
    while time.monotonic()-fault_started<20:
        time.sleep(min(1,20-(time.monotonic()-fault_started)))
    save('tc-end-of-fault.json',tc())
    summary['orders']=orders
except Exception as error:
    summary['error']=str(error)
finally:
    if created:
        try:
            save('scheduler-delete.json',api('DELETE','/'+name))
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                result=subprocess.run(['k3s','kubectl','get','chaosblade',name,'--ignore-not-found','-o','name'],capture_output=True,text=True,timeout=10)
                if result.returncode==0 and not result.stdout.strip():
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Fault resource still exists after deletion')
            recovered=tc()
            save('tc-after-recovery.json',recovered)
            if 'netem' in recovered['qdisc'] or recovered['filters'].strip():
                raise RuntimeError('Network fault rules remain after CR deletion')
            summary['cleanupStatus']='RESTORED'
        except Exception as error:
            summary['cleanupStatus']='FAILED'
            summary['cleanupError']=str(error)
    save('summary.json',summary)
if 'orders' in summary and summary['cleanupStatus']=='RESTORED':
    for elapsed in range(0,31,5):
        if elapsed:
            time.sleep(5)
        rows=kube('exec','deploy/astronomy-db','--','psql','-U','postgres','-d','astronomy_db','-Atc','SELECT order_id FROM accounting."order" ORDER BY order_id;').splitlines()
        save('database-recovery-'+str(elapsed)+'.json',rows)
    summary['databaseOrderIds']=rows
    summary['acceptedOrderIds']=[o['response']['order']['orderId'] for o in summary['orders'] if 'response' in o]
    summary['missingOrderIds']=[order_id for order_id in summary['acceptedOrderIds'] if order_id not in rows]
    summary['postRecoveryControl']=order('post-recovery-control')
    time.sleep(5)
    rows=kube('exec','deploy/astronomy-db','--','psql','-U','postgres','-d','astronomy_db','-Atc','SELECT order_id FROM accounting."order" ORDER BY order_id;').splitlines()
    summary['postRecoveryControlDelivered']=summary['postRecoveryControl'].get('response',{}).get('order',{}).get('orderId') in rows
    save('database-final.json',rows)
    for name in ('payment','shipping','accounting','checkout'):
        (output/(name+'.log')).write_text(kube('logs','deploy/'+name,'--since=10m'))
summary['endedAt']=datetime.now(timezone.utc).isoformat()
save('summary.json',summary)
print(json.dumps({'outputDirectory':str(output),'conclusion':summary['conclusion'],'cleanupStatus':summary['cleanupStatus'],
                  'accepted':len(summary.get('acceptedOrderIds',[])),'missingOrderIds':summary.get('missingOrderIds'),
                  'postRecoveryControlDelivered':summary.get('postRecoveryControlDelivered'),'error':summary.get('error')},indent=2),flush=True)
raise SystemExit(1 if 'error' in summary or summary['cleanupStatus']!='RESTORED' else 0)