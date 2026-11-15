# Copyright 2025 The ChaosBlade Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM openjdk:21-jdk-slim

# 设置工作目录
WORKDIR /app

# 复制 JAR 文件到容器中
COPY ./target/svc-topo-1.0.0.jar ./svc-topo.jar

# 暴露端口
EXPOSE 8106

# 设置环境变量，用于配置 Jaeger 和服务名称
# 如果环境变量未指定，则使用 application.yml 中的默认配置
ENV JaegerHost=""
ENV JaegerPort=""
ENV EntryService=""

# 启动应用
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "svc-topo.jar"]