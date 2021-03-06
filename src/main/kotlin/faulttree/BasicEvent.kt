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

package faulttree

import hu.bme.mit.delta.mdd.MddBuilder
import hu.bme.mit.delta.mdd.MddHandle
import hu.bme.mit.delta.mdd.MddVariableDescriptor
import hu.bme.mit.delta.mdd.MddVariableOrder
import org.ejml.simple.SimpleMatrix
import solver.CoreTensor
import solver.eye
import solver.mat
import solver.r

class BasicEvent(name: String, val failureRate: Double, val dormancy: Double = 1.0, val repairRate: Double = 0.0): AbstractBasicEvent(name,repairRate > 0.0) {
    override fun getVariable(): DFTVar {
        return variable
    }

    override fun getAbsorbingStatesAsMdd(order: MddVariableOrder): MddHandle {
        val builder = MddBuilder<Boolean>(order.createSignatureFromTraceInfos(listOf(name)))
        return if(repairRate == 0.0) builder.build(arrayOf(1), true)
        else builder.build(mutableListOf(arrayOf(0), arrayOf(1)), false)
    }

    override fun getSteadyStateVector(): SimpleMatrix {
        return mat[r[repairRate/(failureRate+repairRate)], r[failureRate/(failureRate+repairRate)]]
    }

    companion object {
        class BasicEventVar(val event: BasicEvent): DFTVar(event.descriptor) {

            fun getKronsumTerm(): SimpleMatrix {
                return mat[
                        r[0, event.failureRate],
                        r[event.repairRate, 0]
                ]
            }

            override fun getBaseCore(prevRank: Int, isLast: Boolean): CoreTensor {
                when {
                    prevRank == 1 -> {
                        val newCore = CoreTensor(4, 1, 2)
                        newCore.data[0] = mat[r[0.0, 1.0]]
                        newCore.data[1] = mat[r[event.failureRate, 0.0]]
                        newCore.data[2] = mat[r[event.repairRate, 0.0]]
                        newCore.data[3] = mat[r[0.0, 1.0]]
                        return newCore
                    }
                    isLast -> {
                        val newCore = CoreTensor(4, 2, 1)
                        newCore.data[0] = mat[r[1.0], r[0.0]]
                        newCore.data[1] = mat[r[0.0], r[event.failureRate]]
                        newCore.data[2] = mat[r[0.0], r[event.repairRate]]
                        newCore.data[3] = mat[r[1.0], r[0.0]]
                        return newCore
                    }
                    else -> {
                        val newCore = CoreTensor(4, 2, 2)
                        newCore[0] = eye(2)
                        newCore[1] = mat[
                                r[0.0, 0.0],
                                r[event.failureRate, 0.0]
                        ]
                        newCore[2] = mat[
                                r[0.0, 0.0],
                                r[event.repairRate, 0.0]
                        ]
                        newCore[3] = eye(2)
                        return newCore
                    }
                }
            }

        }
    }

    private val descriptor = MddVariableDescriptor.create(name, 2)
    val variable = BasicEventVar(this)
    override fun getVariables(): HashMap<MddVariableDescriptor, DFTVar> {
        return hashMapOf(descriptor to this.variable)
    }

    override fun getOrderingWeight(): Double {
        return 0.5
    }

    override fun nonFailureAsMdd(order: MddVariableOrder): MddHandle {
        val builder = MddBuilder<Boolean>(order.createSignatureFromTraceInfos(listOf(name)))
        return builder.build(arrayOf(0), true)
    }

    override fun failureAsMdd(order: MddVariableOrder): MddHandle {
        val builder = MddBuilder<Boolean>(order.createSignatureFromTraceInfos(listOf(name)))
        return builder.build(arrayOf(1), true)
    }

    override fun getBasicEvents(): Set<BasicEvent> {
        return hashSetOf(this)
    }
}