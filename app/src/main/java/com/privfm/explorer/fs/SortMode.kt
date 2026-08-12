// SPDX-License-Identifier: GPL-3.0-or-later
package com.privfm.explorer.fs

/**
 * 一覧のソート方式。(AOSP DocumentsUIのソートメニュー構成を参考に、名前/サイズ/種別を用意)
 * ディレクトリは常に先頭に固定し、選択された基準は同ランク内での並び順にのみ適用する。
 */
enum class SortMode(val label: String) {
    NAME("名前"),
    SIZE("サイズ"),
    TYPE("種類")
}

/**
 * 自然順(natural order)比較。"file2" が "file10" より前に来るよう、
 * 文字列中の数字部分をひとかたまりの数値として比較する
 * (一般的なファイルマネージャー/OSでの並び替え体験を踏襲した独自実装)。
 */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                var sj = j
                while (si < a.length && a[si].isDigit()) si++
                while (sj < b.length && b[sj].isDigit()) sj++
                val numA = a.substring(i, si).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(j, sj).trimStart('0').ifEmpty { "0" }
                val cmp = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = si
                j = sj
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

fun sortEntries(entries: List<FileEntry>, mode: SortMode, ascending: Boolean = true): List<FileEntry> {
    val comparator: Comparator<FileEntry> = when (mode) {
        SortMode.NAME -> Comparator { x, y -> NaturalOrderComparator.compare(x.name.lowercase(), y.name.lowercase()) }
        SortMode.SIZE -> compareBy { it.sizeBytes }
        SortMode.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
    }
    val dirFirst = compareByDescending<FileEntry> { it.isDirectory }
    val finalComparator = if (ascending) dirFirst.thenComparing(comparator) else dirFirst.thenComparing(comparator.reversed())
    return entries.sortedWith(finalComparator)
}
