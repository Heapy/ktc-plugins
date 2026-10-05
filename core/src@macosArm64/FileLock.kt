@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.ktcplugins

internal actual fun fileLock(fd: Int, operation: Int): Int = platform.posix.flock(fd, operation)
