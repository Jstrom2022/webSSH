#!/bin/bash
set -e

echo "1. 清理并在本地打包..."
mvn clean package -DskipTests

echo "2. 准备 release 目录..."
rm -rf release
mkdir -p release/config

# 找到生成的 JAR 包
JAR_FILE=$(ls target/*.jar | head -n 1)
if [ -z "$JAR_FILE" ]; then
    echo "构建失败：未找到 JAR 文件"
    exit 1
fi

# 复制到 release 目录
cp "$JAR_FILE" release/webssh.jar
# 复制启动脚本
cp start.sh release/
# 复制配置文件到 config 目录 (方便服务器修改)
cp src/main/resources/application.properties release/config/
chmod +x release/start.sh

echo "---------------------------------------"
echo "打包成功！发布文件已生成在 release/ 目录："
ls -F release/
echo "你可以将 release 目录上传到服务器，执行 ./start.sh 启动。"
echo "---------------------------------------"
