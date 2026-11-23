#ifndef PERFLAB_WINSOCK_PROBE_H
#define PERFLAB_WINSOCK_PROBE_H

#include <winsock2.h>
#include <windows.h>
#include <stdio.h>

static inline int perflab_probe_iocp(void) {
    WSADATA winsock;
    const int startup = WSAStartup(MAKEWORD(2, 2), &winsock);
    if (startup != 0) {
        fprintf(stderr, "WSAStartup failed (%d)\n", startup);
        return 1;
    }

    SOCKET server = INVALID_SOCKET, peer = INVALID_SOCKET, connection = INVALID_SOCKET;
    HANDLE port = NULL;
    const char *stage = "create loopback listener";
    int result = 1, pending = 0;
    char received = 0;
    WSABUF buffer = {1, &received};
    OVERLAPPED operation = {0};
    DWORD flags = 0;
    struct sockaddr_in address = {0};
    int address_size = sizeof(address);
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    server = WSASocketW(AF_INET, SOCK_STREAM, IPPROTO_TCP, NULL, 0, WSA_FLAG_OVERLAPPED);
    if (server == INVALID_SOCKET || bind(server, (const struct sockaddr *)&address, sizeof(address)) != 0 ||
        getsockname(server, (struct sockaddr *)&address, &address_size) != 0 || listen(server, 1) != 0) {
        goto cleanup;
    }
    stage = "connect and accept loopback socket";
    peer = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (peer == INVALID_SOCKET || connect(peer, (const struct sockaddr *)&address, sizeof(address)) != 0) goto cleanup;
    connection = accept(server, NULL, NULL);
    if (connection == INVALID_SOCKET) goto cleanup;

    stage = "associate accepted socket with IOCP";
    const ULONG_PTR connection_key = 42;
    port = CreateIoCompletionPort(INVALID_HANDLE_VALUE, NULL, 0, 1);
    if (port == NULL || CreateIoCompletionPort((HANDLE)connection, port, connection_key, 0) != port) goto cleanup;

    for (int cancel = 0; cancel <= 1; ++cancel) {
        flags = 0;
        DWORD bytes = 0;
        ULONG_PTR key = 0;
        LPOVERLAPPED completed = NULL;
        stage = "submit WSARecv";
        if (WSARecv(connection, &buffer, 1, NULL, &flags, &operation, NULL) != 0 &&
            WSAGetLastError() != WSA_IO_PENDING) goto cleanup;
        pending = 1;
        if (cancel) {
            stage = "cancel outstanding WSARecv";
            if (!CancelIoEx((HANDLE)connection, &operation)) goto cleanup;
        } else {
            stage = "send probe byte over loopback";
            if (send(peer, "N", 1, 0) != 1) goto cleanup;
        }

        stage = cancel ? "dequeue cancelled receive" : "dequeue successful receive";
        const BOOL ok = GetQueuedCompletionStatus(port, &bytes, &key, &completed, 5000);
        const DWORD status = ok ? ERROR_SUCCESS : GetLastError();
        if (completed == &operation) pending = 0;
        if (pending || key != connection_key) goto cleanup;
        if (cancel ? status != ERROR_OPERATION_ABORTED : (!ok || bytes != 1 || received != 'N')) {
            fprintf(stderr, "Unexpected receive result (status %lu, bytes %lu)\n",
                    (unsigned long)status, (unsigned long)bytes);
            goto cleanup;
        }
        ZeroMemory(&operation, sizeof(operation));
    }
    result = 0;

cleanup:
    if (result != 0) fprintf(stderr, "%s failed (Windows code %lu)\n", stage, (unsigned long)GetLastError());
    if (connection != INVALID_SOCKET) closesocket(connection);
    if (pending) {
        DWORD bytes;
        ULONG_PTR key;
        LPOVERLAPPED completed = NULL;
        GetQueuedCompletionStatus(port, &bytes, &key, &completed, 5000);
        if (completed != &operation) {
            fputs("Receive storage still owned by Windows after closing the probe socket\n", stderr);
            ExitProcess(1);
        }
    }
    if (port != NULL) CloseHandle(port);
    if (peer != INVALID_SOCKET) closesocket(peer);
    if (server != INVALID_SOCKET) closesocket(server);
    WSACleanup();
    return result;
}

#endif
