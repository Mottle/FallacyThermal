package dev.deepslate.fallacy.thermal

import dev.deepslate.fallacy.thermal.data.HeatProcessQueue
import dev.deepslate.fallacy.thermal.impl.EnvironmentThermodynamicsEngine
import dev.deepslate.fallacy.utils.Worker
import net.minecraft.core.BlockPos
import net.minecraft.util.thread.ProcessorMailbox
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.concurrent.ConcurrentSkipListSet

class ChunkScanner(
    val engine: EnvironmentThermodynamicsEngine,
    val heatQueue: HeatProcessQueue
) {

    private val record = ConcurrentSkipListSet<Long>()

    private val mailbox =
        ProcessorMailbox.create(Worker.IO_POOL, "fallacy-thermodynamics-scan")

    val taskCount: Int
        get() = mailbox.size()

    fun enqueue(chunk: ChunkAccess) {
        val state = getProcessState(chunk)
        if (state == HeatProcessState.CORRECTED || state == HeatProcessState.PENDING) return
        forceEnqueue(chunk)
    }

    fun forceEnqueue(chunk: ChunkAccess) {
        setProcessState(chunk, HeatProcessState.PENDING)
        record.add(chunk.pos.toLong())
        mailbox.tell {
            scanSources(chunk)
        }
    }

    fun getProcessState(chunk: ChunkAccess): HeatProcessState = chunk.getData(ModAttachments.HEAT_PROCESS_STATE)

    fun setProcessState(chunk: ChunkAccess, state: HeatProcessState) {
        chunk.setData(ModAttachments.HEAT_PROCESS_STATE, state)
    }


    private fun scanSources(chunk: ChunkAccess) {
        val sections = chunk.sections
        val startPos = chunk.pos.worldPosition
        val positions = mutableListOf<BlockPos>()

        for (sectionIdx in 0 until sections.size) {
            val section = sections[sectionIdx] ?: continue
            val sectionY = engine.level.minBuildHeight + sectionIdx * 16

            if (section.hasOnlyAir()) continue
            if (!section.maybeHas { ThermodynamicsEngine.Companion.isHeatSource(it) }) continue

            val states = section.states

            for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                val state = states[x, y, z]
                if (!ThermodynamicsEngine.Companion.isHeatSource(state)) continue

                val currentPos = BlockPos(startPos.x + x, sectionY + y, startPos.z + z)

                positions.add(currentPos)
            }
        }

        heatQueue.enqueueAll(chunk.pos, positions, true)
        record.remove(chunk.pos.toLong())
//        setProcessState(chunk, HeatProcessState.CORRECTED)
    }

    fun stop() {
        mailbox.close()

        record.forEach {
            val chunkPos = ChunkPos(it)
            val chunk = engine.level.getChunk(chunkPos.x, chunkPos.z)
            setProcessState(chunk, HeatProcessState.STERN)
        }
    }
}