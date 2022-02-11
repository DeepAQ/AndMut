package com.github.mut.android.client.service

import java.net.InetAddress
import java.nio.ByteBuffer

typealias CIDR = ULong

fun CIDR.prefixLength(): Int {
    return this.and(0xffffffffu).toInt()
}

fun CIDR.prefixAddress(): InetAddress {
    return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(this.shr(32).toInt()).array())
}

fun CIDR.contains(other: CIDR): Boolean {
    val prefixLen = this.prefixLength()
    if (prefixLen == 0) {
        return true
    }
    return prefixLen <= other.prefixLength() && this.shr(64 - prefixLen) == other.shr(64 - prefixLen)
}

fun CIDR.subtract(other: CIDR): List<CIDR> {
    if (this == other) {
        return emptyList()
    }
    if (!this.contains(other)) {
        return listOf(this)
    }
    return this.prefixLength().inc().rangeTo(other.prefixLength()).map {
        other.and((1uL.shl(64 - it) - 1u).inv()).xor(1uL.shl(64 - it)) + it.toUInt()
    }.toList()
}

fun stringToCIDR(str: String): CIDR {
    val prefixAndLength = str.split('/', limit = 2)
    val prefix = ByteBuffer.wrap(InetAddress.getByName(prefixAndLength[0]).address).int
    val length = if (prefixAndLength.size < 2) 32u else prefixAndLength[1].toUInt()
    return prefix.toULong().shl(32) + length
}

fun getRoutesFromExcludedRoutes(excluded: List<String>): List<CIDR> {
    val routes = mutableSetOf(0uL)
    excluded.map { stringToCIDR(it) }.forEach {
        for (cidr in routes.toList()) {
            if (cidr.contains(it)) {
                routes.remove(cidr)
                routes.addAll(cidr.subtract(it))
                break
            }
        }
    }
    return routes.toList()
}