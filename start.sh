#!/bin/bash

APP_NAME=$(ls *.jar 2>/dev/null | head -n 1)
PID_FILE="app.pid"
LOG_FILE="app.log"

if [ -z "$APP_NAME" ]; then
    echo "错误: 未找到 JAR 文件"
    exit 1
fi

get_pid() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            echo "$pid"
            return 0
        fi
        rm -f "$PID_FILE"
    fi
    return 1
}

do_start() {
    local pid
    if pid=$(get_pid); then
        echo "应用已在运行中 (PID: $pid)"
        return 1
    fi
    echo "正在启动 $APP_NAME ..."
    JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/}"
    nohup java $JAVA_OPTS -jar "$APP_NAME" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    echo "应用已在后台启动 (PID: $!)，日志请查看 $LOG_FILE"
}

do_stop() {
    local pid
    if ! pid=$(get_pid); then
        echo "应用未在运行"
        return 1
    fi
    echo "正在停止应用 (PID: $pid) ..."
    kill "$pid"
    local timeout=30
    while kill -0 "$pid" 2>/dev/null && [ $timeout -gt 0 ]; do
        sleep 1
        timeout=$((timeout - 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "正常停止超时，强制终止 ..."
        kill -9 "$pid"
    fi
    rm -f "$PID_FILE"
    echo "应用已停止"
}

do_status() {
    local pid
    if pid=$(get_pid); then
        echo "应用正在运行 (PID: $pid)"
    else
        echo "应用未在运行"
    fi
}

do_restart() {
    do_stop
    sleep 1
    do_start
}

case "$1" in
    start)
        do_start
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_restart
        ;;
    status)
        do_status
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status}"
        exit 1
        ;;
esac
