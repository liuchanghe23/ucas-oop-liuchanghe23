"""Send three demo orders and verify their accounting records before a T11 diagnostic."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--lab-dir',type=Path,default=Path(__file__).resolve().parents[1]/'.local/t11-tools')
root=parser.parse_args().lab_dir.resolve()

def kube(*args):
    return subprocess.check_output(['k3s','kubectl','-n','oop-t11',*args], text=True)

def service(name):
    return json.loads(kube('get','service',name,'-o','json'))['spec']['clusterIP']

def rpc(service_name,port,method,data):
    result=subprocess.run([str(root/'grpcurl'),'-plaintext','-max-time','15','-import-path',str(root/'otel-3.1.0/pb'),'-proto','demo.proto','-d',json.dumps(data),f'{service(service_name)}:{port}','oteldemo.'+method],capture_output=True,text=True,timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout)

orders=[]
for index in range(3):
    user='t11-baseline-'+uuid.uuid4().hex
    rpc('cart',7070,'CartService/AddItem',{'userId':user,'item':{'productId':'L9ECAV7KIM','quantity':1}})
    result=rpc('checkout',5050,'CheckoutService/PlaceOrder',{'userId':user,'userCurrency':'USD','address':{'streetAddress':'1 Test Street','city':'Test City','state':'CA','country':'United States','zipCode':'94043'},'email':'t11@example.invalid','creditCard':{'creditCardNumber':'4111111111111111','creditCardCvv':123,'creditCardExpirationYear':2030,'creditCardExpirationMonth':12}})
    orders.append(result)
    print(json.dumps(result),flush=True)
time.sleep(5)
query='SELECT order_id FROM accounting."order" ORDER BY order_id;'
rows=kube('exec','deploy/astronomy-db','--','psql','-U','postgres','-d','astronomy_db','-Atc',query).splitlines()
(root/'baseline.json').write_text(json.dumps({'orders':orders,'databaseOrderIds':rows},indent=2))
print('Baseline delivered '+str(sum(order['order']['orderId'] in rows for order in orders))+'/'+str(len(orders)))
raise SystemExit(0 if len(orders)==3 and all(order['order']['orderId'] in rows for order in orders) else 1)
