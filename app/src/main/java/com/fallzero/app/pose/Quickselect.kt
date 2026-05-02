package com.fallzero.app.pose

/**
 * Quickselect — FloatArray에서 k번째 작은 원소를 in-place로 찾음 (Hoare partition).
 * 평균 O(n), 최악 O(n²) — 우리 사용처(circular ring buffer)는 임의 순서이므로 평균에 가까움.
 *
 * 사용처: CalfRaise/ToeRaise rolling buffer baseline 계산.
 *   기존: array.copyOf().also { it.sort() }[pctIdx] — O(n log n) ≈ 600 ops/frame on n=90
 *   개선: nthSmallest(array.copyOf(), pctIdx) — O(n) ≈ 90 ops/frame on n=90
 *
 * 호출 후 array는 k 위치 기준으로 부분 정렬된 상태가 됨 (원본 보존이 필요하면 copy를 먼저).
 * 반환값: 정렬된 array의 [k] 위치 값과 동일.
 */
internal fun nthSmallest(array: FloatArray, k: Int, lo: Int = 0, hi: Int = array.size - 1): Float {
    if (lo == hi) return array[lo]
    var l = lo
    var h = hi
    while (l < h) {
        val pivot = array[(l + h) ushr 1]
        var i = l
        var j = h
        while (i <= j) {
            while (array[i] < pivot) i++
            while (array[j] > pivot) j--
            if (i <= j) {
                val tmp = array[i]; array[i] = array[j]; array[j] = tmp
                i++; j--
            }
        }
        if (k <= j) h = j
        else if (k >= i) l = i
        else return array[k]
    }
    return array[k]
}
