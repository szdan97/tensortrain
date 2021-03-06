/*
 *
 *   Copyright 2021 Budapest University of Technology and Economics
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package parser

import faulttree.*
import org.antlr.v4.runtime.Token
import org.ejml.simple.SimpleMatrix
import java.lang.UnsupportedOperationException

class GalileoListenerImpl : GalileoBaseListener() {
    lateinit var faultTree: FaultTree private set

    sealed class GateType {
        object OR : GateType()
        object AND : GateType()
        data class K_OF_N(val k: Int, val n: Int) : GateType()
        object WSP : GateType()
    }

    private data class PendingNode(val name: String, val type: GateType, val inputs: Collection<String>)

    private var faultTreeName: String? = null
    private val pendingFTNodes = arrayListOf<PendingNode>()
    private val createdFaultTreeNodes = hashMapOf<String, FaultTreeNode>()

    override fun enterGate(ctx: GalileoParser.GateContext) {
        val newNode = when {
            ctx.operation().or() != null -> PendingNode(ctx.name.text, GateType.OR, ctx.inputs.map(Token::getText))
            ctx.operation().and() != null -> PendingNode(ctx.name.text, GateType.AND, ctx.inputs.map(Token::getText))
            ctx.operation().of() != null -> {
                val k = ctx.operation().of().k.text.toInt()
                val n = ctx.operation().of().n.text.toInt()
                PendingNode(ctx.name.text, GateType.K_OF_N(k, n), ctx.inputs.map(Token::getText))
            }
            ctx.operation().wsp() != null -> PendingNode(ctx.name.text, GateType.WSP, ctx.inputs.map(Token::getText))
            else -> throw Exception("Unknown type \"${ctx.operation().text}\" for node ${ctx.name.text} ")
        }
        if (!tryToProcess(newNode)) pendingFTNodes.add(newNode)
    }

    override fun enterBasicevent(ctx: GalileoParser.BasiceventContext) {
        val name = ctx.name.text
        val lambda = ctx.property().find { it.lambda() != null }?.lambda()?.`val`?.text?.toDouble()
        val mu = ctx.property().find { it.repair() != null }?.repair()?.`val`?.text?.toDouble() ?: 0.0
        val dorm = ctx.property().find { it.dormancy() != null }?.dormancy()?.`val`?.text?.toDouble() ?: 1.0
        val phase = ctx.property().find { it.phase() != null }?.phase()?.`val`
        val numFailureStates = (ctx.property().find { it.numFailureStates() != null }?.numFailureStates()?.`val`?.text
                                ?: "1").toInt()
        if (lambda != null)
            addFTNode(name, BasicEvent(name, lambda, dorm, repairRate = mu))
        else if (phase != null) {
            val rateMatrix = parseMatrix(phase)
            if (rateMatrix.numRows() < numFailureStates)
                throw RuntimeException("Error when parsing event $name: number of failure states cannot be larger than " +
                                       "the number of states given by the rate matrix")
            addFTNode(name, PHBasicEvent(name, rateMatrix, numFailureStates))
        } else throw RuntimeException("No failure distribution specified for event $name!")
    }

    private fun parseMatrix(matrixCtx: GalileoParser.RateMatrixContext): SimpleMatrix {
        val rowCtxs = matrixCtx.matrixRow()
        val ret = SimpleMatrix(rowCtxs.size, rowCtxs.size)
        for ((i, rowCtx) in rowCtxs.withIndex()) {
            if (rowCtx.vals.size != ret.numCols())
                throw RuntimeException("Error when parsing rate matrix: number of columns in a row must be the " +
                                       "same as the number of rows")
            for ((j, node) in rowCtx.vals.withIndex()) {
                ret[i, j] = node.text.toDouble()
            }
        }
        return ret
    }

    private fun addFTNode(name: String, node: FaultTreeNode) {
        createdFaultTreeNodes[name] = node
        pendingFTNodes.filter { it.inputs.contains(name) }.forEach { pendingNode ->
            //needed because calling addFTNode in prev iterations can change the pending nodes collection TODO: better solution
            if (pendingFTNodes.contains(pendingNode)) tryToProcess(pendingNode)
        }
    }

    private fun tryToProcess(pendingNode: PendingNode): Boolean {
        if (pendingNode.inputs.all(createdFaultTreeNodes::containsKey)) {
            pendingFTNodes.remove(pendingNode)
            addFTNode(pendingNode.name, instantiateNode(pendingNode))
            return true
        }
        return false
    }

    /**
     * Creates a FaultTreeNode instance described by a PendingNodeInstance. Must be called only when all inputs have
     * been created, and added to the createdFaulTreeNodes map!!!
     */
    private fun instantiateNode(pendingNode: PendingNode): FaultTreeNode = when (pendingNode.type) {
        is GateType.AND -> AndGate(*pendingNode.inputs.map { createdFaultTreeNodes[it]!! }.toTypedArray())
        is GateType.OR -> OrGate(*pendingNode.inputs.map { createdFaultTreeNodes[it]!! }.toTypedArray())
        is GateType.K_OF_N -> VotingGate(pendingNode.type.k, *pendingNode.inputs.map { createdFaultTreeNodes[it]!! }.toTypedArray())
        is GateType.WSP -> wspToPhaseTypeBE(pendingNode)
    }

    private fun wspToPhaseTypeBE(wsp: PendingNode): PHBasicEvent {
        check(wsp.type == GateType.WSP)
        val rateMatrix = SimpleMatrix(wsp.inputs.size+1, wsp.inputs.size+1)
        for((i, inpName) in wsp.inputs.withIndex()) {
            val inp = createdFaultTreeNodes[inpName]
            if(inp !is BasicEvent || inp.dormancy > 0)
                throw UnsupportedOperationException("Only WSPs with 0-dorm exponential basic event inputs are supported yet.")
            if(inp.repairRate > 0)
                throw UnsupportedOperationException("Inputs of dynamic gates must not be repairable.")
            //TODO: check that the input is not an input of any other gate

            rateMatrix[i, i+1] = inp.failureRate
        }
        return PHBasicEvent(wsp.name, rateMatrix, 1)
    }

    override fun enterTop(ctx: GalileoParser.TopContext) {
        val name = ctx.name.text
        faultTreeName = name
    }

    override fun exitFaulttree(ctx: GalileoParser.FaulttreeContext) {
        val topnode = createdFaultTreeNodes[faultTreeName as String?]
                      ?: throw Exception("Top event not found")
        faultTree = FaultTree(topnode)
    }
}