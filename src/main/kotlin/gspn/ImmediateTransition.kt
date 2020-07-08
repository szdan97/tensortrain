package gspn

import hu.bme.mit.delta.mdd.MddVariableOrder
import solver.CoreTensor
import solver.TTSquareMatrix
import solver.TTVector
import solver.TensorTrain

class ImmediateTransition(
        name: String,
        inputs: ArrayList<Arc>,
        outputs: ArrayList<Arc>,
        inhibitors: ArrayList<Arc>,
        val priority: Int
) : Transition(name, inputs, outputs, inhibitors) {
    override fun toTT(varOrder: MddVariableOrder, places: ArrayList<Place>): TTSquareMatrix {
        val rateVector = TTVector.ones(places.map { it.capacity+1 }.toTypedArray())
        val matrixCores = arrayListOf<CoreTensor>()
        for ((idx, variable) in varOrder.withIndex()) {
            val core = rateVector.tt.cores[idx]
            val place = places.firstOrNull{it.name == variable.traceInfo} ?: throw RuntimeException("Place ${variable.traceInfo} not found")
            // TODO: these searches assume that at most one arc exists from a given place - this should be enforced
            val inp = inputs.firstOrNull { it.place == place}?.weightFunction ?: {0}
            val out = outputs.firstOrNull {it.place.name == variable.traceInfo }?.weightFunction ?: {0}
            val inh = inhibitors.firstOrNull { it.place.name == variable.traceInfo}?.weightFunction ?: {Int.MAX_VALUE}
            val newMatCore = CoreTensor(core.modeLength*core.modeLength, core.rows, core.cols)
            for(m in 0..place.capacity) {
                val result = m - inp(m) + out(m)
                if(m >= inp(m) && m < inh(m) && result <= place.capacity)
                    newMatCore[m, result] = core[m]
            }
            matrixCores.add(newMatCore)
        }
        return TTSquareMatrix(TensorTrain(matrixCores), rateVector.modes)
    }
}