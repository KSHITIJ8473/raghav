package com.laddu100.kuroanime

class MiniWasm(bytes: ByteArray) {

    private val memory = ByteArray(1 shl 16)
    private val globals = IntArray(8)
    private val funcs = mutableListOf<Pair<IntArray, ByteArray>>()
    private val exports = mutableMapOf<String, Int>()

    private class Label(val isLoop: Boolean, val body: Int, val end: Int)

    init {
        require(bytes.size >= 8 && bytes[0] == 0x00.toByte() && bytes[1] == 0x61.toByte()) { "not a wasm module" }
        var i = 8
        while (i < bytes.size) {
            val sid = bytes[i].toInt() and 0xFF
            val (size, bodyStart) = leu(bytes, i + 1)
            val end = bodyStart + size
            when (sid) {
                7 -> {
                    var j = bodyStart
                    val n = leu(bytes, j).first
                    j = leu(bytes, j).second
                    repeat(n) {
                        val nl = bytes[j].toInt() and 0xFF; j++
                        val name = String(bytes, j, nl, Charsets.UTF_8); j += nl
                        val kind = bytes[j].toInt() and 0xFF; j++
                        val idx = leu(bytes, j).also { j = it.second }.first
                        if (kind == 0) exports[name] = idx
                    }
                }
                10 -> {
                    var j = bodyStart
                    val n = leu(bytes, j).first
                    j = leu(bytes, j).second
                    repeat(n) {
                        val bsize = leu(bytes, j).first
                        j = leu(bytes, j).second
                        val body = bytes.copyOfRange(j, j + bsize)
                        j += bsize
                        var p = 0
                        val nGroups = leu(body, p).first
                        p = leu(body, p).second
                        val locals = mutableListOf<Int>()
                        repeat(nGroups) {
                            val cnt = leu(body, p).first
                            p = leu(body, p).second
                            val type = body[p].toInt() and 0xFF; p++
                            repeat(cnt) { locals.add(type) }
                        }
                        funcs.add(locals.toIntArray() to body.copyOfRange(p, body.size))
                    }
                }
            }
            i = end
        }
    }

    private fun leu(b: ByteArray, off: Int): Pair<Int, Int> {
        var v = 0; var shift = 0; var j = off
        while (true) {
            val x = b[j].toInt() and 0xFF; j++
            v = v or ((x and 0x7F) shl shift)
            shift += 7
            if (x and 0x80 == 0) break
        }
        return v to j
    }

    private fun les(b: ByteArray, off: Int): Pair<Int, Int> {
        var v = 0; var shift = 0; var j = off
        while (true) {
            val x = b[j].toInt() and 0xFF; j++
            v = v or ((x and 0x7F) shl shift)
            shift += 7
            if (x and 0x80 == 0) {
                if (shift < 32 && (x and 0x40) != 0) v = v or (-1 shl shift)
                break
            }
        }
        return v to j
    }

    fun writeMemory(offset: Int, data: ByteArray) {
        if (offset < 0 || offset + data.size > memory.size) return
        System.arraycopy(data, 0, memory, offset, data.size)
    }

    fun readMemory(offset: Int, len: Int): ByteArray = memory.copyOfRange(offset, offset + len)

    fun call(name: String, vararg args: Int) {
        val (localTypes, b) = funcs.getOrNull(exports[name] ?: return) ?: return
        val locals = IntArray(args.size + localTypes.size)
        for (k in args.indices) locals[k] = args[k]

        val endOf = HashMap<Int, Int>()
        val elseOf = HashMap<Int, Int>()
        run {
            val ctl = ArrayDeque<Int>()
            var k = 0
            while (k < b.size) {
                when (b[k].toInt() and 0xFF) {
                    0x02, 0x03, 0x04 -> { ctl.addLast(k); k += 2 }
                    0x05 -> { ctl.lastOrNull()?.let { elseOf[it] = k }; k++ }
                    0x0b -> { ctl.removeLastOrNull()?.let { endOf[it] = k }; k++ }
                    0x41 -> k = les(b, k + 1).second
                    0x20, 0x21, 0x22, 0x23, 0x24, 0x0c, 0x0d, 0x10, 0x11 -> k = leu(b, k + 1).second
                    in 0x28..0x3e -> { k = leu(b, k + 1).second; k = leu(b, k).second }
                    else -> k++
                }
            }
        }

        val stack = ArrayDeque<Int>()
        val labels = ArrayDeque<Label>()
        var pos = 0

        fun branch(depth: Int) {
            val idx = labels.size - 1 - depth
            if (idx < 0) { pos = b.size; return }
            val target = labels.elementAt(idx)
            if (target.isLoop) {
                while (labels.size > idx + 1) labels.removeLastOrNull()
                pos = target.body
            } else {
                while (labels.size > idx) labels.removeLastOrNull()
                pos = target.end + 1
            }
        }

        while (pos < b.size) {
            when (b[pos].toInt() and 0xFF) {
                0x00 -> return
                0x01 -> pos++
                0x02, 0x03 -> {
                    val isLoop = (b[pos].toInt() and 0xFF) == 0x03
                    labels.addLast(Label(isLoop, pos + 2, endOf[pos] ?: (b.size - 1)))
                    pos += 2
                }
                0x04 -> {
                    val cond = stack.removeLastOrNull() ?: 0
                    val endIdx = endOf[pos] ?: (b.size - 1)
                    val elseIdx = elseOf[pos]
                    if (cond != 0) {
                        labels.addLast(Label(false, pos + 2, endIdx))
                        pos += 2
                    } else {
                        if (elseIdx != null) {
                            labels.addLast(Label(false, elseIdx + 1, endIdx))
                            pos = elseIdx + 1
                        } else {
                            pos = endIdx + 1
                        }
                    }
                }
                0x05 -> {
                    val lbl = labels.removeLastOrNull()
                    pos = (lbl?.end ?: (b.size - 1)) + 1
                }
                0x0b -> { labels.removeLastOrNull(); pos++ }
                0x0c -> { val d = leu(b, pos + 1).first; branch(d) }
                0x0d -> {
                    val d = leu(b, pos + 1).first
                    pos = leu(b, pos + 1).second
                    if ((stack.removeLastOrNull() ?: 0) != 0) branch(d)
                }
                0x0f -> return
                0x20 -> { val (i2, j) = leu(b, pos + 1); pos = j; stack.addLast(locals.getOrElse(i2) { 0 }) }
                0x21 -> { val i2 = leu(b, pos + 1).first; pos = leu(b, pos + 1).second; if (i2 < locals.size) locals[i2] = stack.removeLastOrNull() ?: 0 }
                0x22 -> { val i2 = leu(b, pos + 1).first; pos = leu(b, pos + 1).second; if (i2 < locals.size) locals[i2] = stack.lastOrNull() ?: 0 }
                0x23 -> { val i2 = leu(b, pos + 1).first; pos = leu(b, pos + 1).second; stack.addLast(globals.getOrElse(i2) { 0 }) }
                0x24 -> { val i2 = leu(b, pos + 1).first; pos = leu(b, pos + 1).second; if (i2 < globals.size) globals[i2] = stack.removeLastOrNull() ?: 0 }
                0x2d -> {
                    var j = leu(b, pos + 1).second
                    val off = leu(b, j).first; j = leu(b, j).second
                    pos = j
                    val addr = (stack.removeLastOrNull() ?: 0) + off
                    stack.addLast(if (addr in memory.indices) memory[addr].toInt() and 0xFF else 0)
                }
                0x3a -> {
                    var j = leu(b, pos + 1).second
                    val off = leu(b, j).first; j = leu(b, j).second
                    pos = j
                    val v = stack.removeLastOrNull() ?: 0
                    val addr = (stack.removeLastOrNull() ?: 0) + off
                    if (addr in memory.indices) memory[addr] = (v and 0xFF).toByte()
                }
                0x41 -> { val (v, j) = les(b, pos + 1); pos = j; stack.addLast(v) }
                0x45 -> { pos++; stack.addLast(if ((stack.removeLastOrNull() ?: 0) == 0) 1 else 0) }
                0x46 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (x == y) 1 else 0) }
                0x47 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (x != y) 1 else 0) }
                0x49 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) < 0) 1 else 0) }
                0x4a -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) > 0) 1 else 0) }
                0x4b, 0x4d -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) <= 0) 1 else 0) }
                0x4f -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) >= 0) 1 else 0) }
                0x6a -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x + y) }
                0x6b -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x - y) }
                0x6c -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x * y) }
                0x71 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x and y) }
                0x72 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x or y) }
                0x73 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x xor y) }
                0x74 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x shl (y and 31)) }
                0x75 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x shr (y and 31)) }
                0x76 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x ushr (y and 31)) }
                0x77 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; val s = y and 31; stack.addLast(if (s == 0) x else (x shl s) or (x ushr (32 - s))) }
                0x78 -> { pos++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; val s = y and 31; stack.addLast(if (s == 0) x else (x ushr s) or (x shl (32 - s))) }
                else -> return
            }
        }
    }
}
