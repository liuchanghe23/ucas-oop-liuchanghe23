"""Generate manifests for the isolated T11 lab from pinned images and a source snapshot."""
import argparse
import json
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--lab-dir', type=Path, default=Path(__file__).resolve().parents[1] / '.local/t11-tools')
root = parser.parse_args().lab_dir.resolve()
source = root / 'otel-3.1.0'
namespace = 'oop-t11'
lock = json.loads((root / 'image-lock.json').read_text())
images = {item['image']: item['image'].rsplit(':', 1)[0] + '@' + item['digest'] for item in lock}
password = secrets.token_hex(20)
objects = [dict(apiVersion='v1', kind='Namespace', metadata=dict(name=namespace))]

def resource(kind, name, **data):
    return dict(apiVersion='v1', kind=kind, metadata=dict(name=name, namespace=namespace), **data)

init_sql = (source / 'src/postgresql/init.sql').read_text().replace('astronomy_password', password).replace('monitoring_password', secrets.token_hex(20))
objects.append(resource('ConfigMap', 't11-config', data={
    'init.sql': init_sql,
    'demo.flagd.json': (source / 'src/flagd/demo.flagd.json').read_text(),
}))
objects.append(resource('Secret', 't11-db', type='Opaque', stringData={
    'POSTGRES_PASSWORD': secrets.token_hex(20),
    'ACCOUNTING_DB': f'Host=astronomy-db;Username=astronomy_user;Password={password};Database=astronomy_db',
    'CATALOG_DB': f'postgres://astronomy_user:{password}@astronomy-db/astronomy_db?sslmode=disable',
}))
common = dict(FLAGD_HOST='flagd', FLAGD_PORT='8013', IPV6_ENABLED='false',
              OTEL_TRACES_EXPORTER='none', OTEL_METRICS_EXPORTER='none', OTEL_LOGS_EXPORTER='none',
              OTEL_EXPORTER_OTLP_ENDPOINT='http://127.0.0.1:4317', OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE='cumulative',
              OTEL_RESOURCE_ATTRIBUTES='service.namespace=oop-t11,service.version=3.1.0')

def deploy(name, image, port=None, env=None, args=None, mounts=None, volumes=None, secret=None, memory='256Mi'):
    variables = dict(common, OTEL_SERVICE_NAME=name, **(env or {}))
    container = dict(name=name, image=images[image], imagePullPolicy='Never',
                     env=[dict(name=k, value=str(v)) for k,v in variables.items()],
                     resources=dict(requests=dict(cpu='20m', memory='32Mi'), limits=dict(memory=memory)))
    if port:
        container['ports'] = [dict(containerPort=port)]
        container['readinessProbe'] = dict(tcpSocket=dict(port=port), initialDelaySeconds=3, periodSeconds=3)
    if args:
        container['args'] = args
    if mounts:
        container['volumeMounts'] = mounts
    if secret:
        container['env'].extend(dict(name=name, valueFrom=dict(secretKeyRef=dict(name='t11-db', key=key))) for name,key in secret.items())
    pod = dict(containers=[container], terminationGracePeriodSeconds=15)
    if volumes:
        pod['volumes'] = volumes
    objects.append(dict(apiVersion='apps/v1', kind='Deployment', metadata=dict(name=name, namespace=namespace),
                        spec=dict(replicas=1, selector=dict(matchLabels=dict(app=name)),
                                  template=dict(metadata=dict(labels=dict(app=name)), spec=pod))))
    if port:
        service_spec = dict(selector=dict(app=name), ports=[dict(name='main', port=port, targetPort=port)])
        if name == 'kafka':
            service_spec['publishNotReadyAddresses'] = True
            service_spec['ports'].append(dict(name='controller', port=9093, targetPort=9093))
        objects.append(resource('Service', name, spec=service_spec))

def app(name, port, **kwargs):
    deploy(name, 'ghcr.io/open-telemetry/demo:3.1.0-' + name, port, **kwargs)

deploy('astronomy-db', 'docker.io/library/postgres:18.4', 5432,
       args=['postgres','-c','shared_preload_libraries=pg_stat_statements'],
       env={'POSTGRES_USER':'postgres'}, secret={'POSTGRES_PASSWORD':'POSTGRES_PASSWORD'}, memory='512Mi',
       mounts=[dict(name='data',mountPath='/var/lib/postgresql'), dict(name='config',mountPath='/docker-entrypoint-initdb.d/init.sql',subPath='init.sql')],
       volumes=[dict(name='data',emptyDir={}),dict(name='config',configMap=dict(name='t11-config'))])
deploy('flagd','ghcr.io/open-feature/flagd:v0.16.0',8013,
       args=['start','--uri','file:/etc/flagd/demo.flagd.json'],
       mounts=[dict(name='config',mountPath='/etc/flagd')],volumes=[dict(name='config',configMap=dict(name='t11-config'))])
deploy('valkey-cart','ghcr.io/valkey-io/valkey:9.0.4-alpine3.23',6379)
app('kafka',9092,env=dict(KAFKA_ADVERTISED_LISTENERS='PLAINTEXT://kafka:9092',
                        KAFKA_LISTENERS='PLAINTEXT://:9092,CONTROLLER://:9093', KAFKA_CONTROLLER_QUORUM_VOTERS='1@kafka:9093',
                        KAFKA_HEAP_OPTS='-Xmx400m -Xms400m'), memory='1Gi')
app('cart',7070,env=dict(CART_PORT='7070', ASPNETCORE_HTTP_PORTS='7070', VALKEY_ADDR='valkey-cart:6379'))
app('currency',7001,env=dict(CURRENCY_PORT='7001'))
app('quote',8090,env=dict(QUOTE_PORT='8090', OTEL_PHP_AUTOLOAD_ENABLED='false'))
app('shipping',50050,env=dict(SHIPPING_PORT='50050', QUOTE_ADDR='http://quote:8090'))
app('payment',50051,env=dict(PAYMENT_PORT='50051'))
app('email',6060,env=dict(EMAIL_PORT='6060',APP_ENV='production'), memory='384Mi')
app('product-catalog',3550,env=dict(PRODUCT_CATALOG_PORT='3550'),secret={'DB_CONNECTION_STRING':'CATALOG_DB'})
app('accounting',None,env=dict(KAFKA_ADDR='kafka:9092',KAFKA_TOPIC='orders',OTEL_DOTNET_AUTO_TRACES_ENTITYFRAMEWORKCORE_INSTRUMENTATION_ENABLED='false'),
    secret={'DB_CONNECTION_STRING':'ACCOUNTING_DB'},memory='384Mi')
app('checkout',5050,env=dict(CHECKOUT_PORT='5050', CART_ADDR='cart:7070', CURRENCY_ADDR='currency:7001',
                           EMAIL_ADDR='http://email:6060', PAYMENT_ADDR='payment:50051', PRODUCT_CATALOG_ADDR='product-catalog:3550',
                           SHIPPING_ADDR='http://shipping:50050', KAFKA_ADDR='kafka:9092', KAFKA_TOPIC='orders'))
(root / 'target-deployment.json').write_text(json.dumps(dict(apiVersion='v1',kind='List',items=objects),indent=2))
print('Prepared isolated oop-t11 deployment with 13 components; secrets remain local')