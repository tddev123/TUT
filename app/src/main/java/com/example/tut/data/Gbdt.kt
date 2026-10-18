package com.example.tut.data

import kotlin.math.abs
import kotlin.random.Random

/**
 * A small gradient-boosted regression tree model (squared error). Each tree is fitted to what
 * the trees before it still get wrong, and adds a damped step ([learningRate]) of its guess.
 * Plain Kotlin, no libraries: the data sets here are a few hundred workouts at most.
 */
class Gbdt private constructor(
    private val base: Double,
    private val learningRate: Double,
    private val trees: List<Node>,
) {
    private sealed interface Node {
        class Leaf(val value: Double) : Node
        /** Rows with x[feature] <= threshold go left. */
        class Split(val feature: Int, val threshold: Double, val left: Node, val right: Node) : Node
    }

    /** A read-only copy of one tree, for the prediction model page. */
    sealed interface TreeView {
        data class Leaf(val value: Double) : TreeView
        /** Rows with x[feature] <= threshold go left. */
        data class Split(val feature: Int, val threshold: Double, val left: TreeView, val right: TreeView) : TreeView
    }

    /** The starting guess every tree adds to. */
    val baseValue: Double get() = base
    val rate: Double get() = learningRate
    val treeViews: List<TreeView> by lazy { trees.map(::view) }

    private fun view(n: Node): TreeView = when (n) {
        is Node.Leaf -> TreeView.Leaf(n.value)
        is Node.Split -> TreeView.Split(n.feature, n.threshold, view(n.left), view(n.right))
    }

    fun predict(x: DoubleArray): Double {
        var y = base
        for (t in trees) y += learningRate * leaf(t, x)
        return y
    }

    private fun leaf(n: Node, x: DoubleArray): Double = when (n) {
        is Node.Leaf -> n.value
        is Node.Split -> leaf(if (x[n.feature] <= n.threshold) n.left else n.right, x)
    }

    companion object {
        fun fit(
            x: List<DoubleArray>,
            y: DoubleArray,
            trees: Int = 150,
            depth: Int = 3,
            learningRate: Double = 0.05,
            minLeaf: Int = 2,
            subsample: Double = 0.8,
            seed: Int = 7,
        ): Gbdt {
            require(x.size == y.size && x.isNotEmpty())
            val n = y.size
            val base = y.average()
            val pred = DoubleArray(n) { base }
            val random = Random(seed)
            val built = ArrayList<Node>(trees)
            repeat(trees) {
                val residual = DoubleArray(n) { y[it] - pred[it] }
                // Each tree sees a random part of the rows, which keeps it from memorising them.
                val rows = (0 until n).filter { n < 4 || random.nextDouble() < subsample }.ifEmpty { (0 until n).toList() }
                val tree = grow(x, residual, rows, depth, minLeaf)
                built += tree
                for (i in 0 until n) pred[i] += learningRate * evalNode(tree, x[i])
            }
            return Gbdt(base, learningRate, built)
        }

        private fun evalNode(n: Node, x: DoubleArray): Double = when (n) {
            is Node.Leaf -> n.value
            is Node.Split -> evalNode(if (x[n.feature] <= n.threshold) n.left else n.right, x)
        }

        private fun grow(x: List<DoubleArray>, r: DoubleArray, rows: List<Int>, depth: Int, minLeaf: Int): Node {
            val mean = rows.sumOf { r[it] } / rows.size
            if (depth == 0 || rows.size < 2 * minLeaf) return Node.Leaf(mean)
            val total = rows.sumOf { r[it] }
            var bestGain = 1e-12
            var bestFeature = -1
            var bestThreshold = 0.0
            val features = x[0].size
            for (f in 0 until features) {
                val sorted = rows.sortedBy { x[it][f] }
                var leftSum = 0.0
                for (k in 0 until sorted.size - 1) {
                    leftSum += r[sorted[k]]
                    val nl = k + 1
                    val nr = sorted.size - nl
                    val here = x[sorted[k]][f]
                    val next = x[sorted[k + 1]][f]
                    if (here == next || nl < minLeaf || nr < minLeaf) continue
                    val rightSum = total - leftSum
                    // Squared-error reduction compared with not splitting.
                    val gain = leftSum * leftSum / nl + rightSum * rightSum / nr - total * total / sorted.size
                    if (gain > bestGain) {
                        bestGain = gain
                        bestFeature = f
                        bestThreshold = if (abs(next - here) > 1e5) here else (here + next) / 2
                    }
                }
            }
            if (bestFeature < 0) return Node.Leaf(mean)
            val (left, right) = rows.partition { x[it][bestFeature] <= bestThreshold }
            return Node.Split(
                bestFeature, bestThreshold,
                grow(x, r, left, depth - 1, minLeaf),
                grow(x, r, right, depth - 1, minLeaf),
            )
        }
    }
}
