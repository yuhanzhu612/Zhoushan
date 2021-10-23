/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._
import zhoushan.Constant._

object Csrs {
  val mhartid  = "hf14".U
  val mstatus  = "h300".U
  val mie      = "h304".U
  val mtvec    = "h305".U
  val mscratch = "h340".U
  val mepc     = "h341".U
  val mcause   = "h342".U
  val mip      = "h344".U
  val mcycle   = "hb00".U
  val minstret = "hb02".U
}

class Csr extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val jmp_packet = Output(new JmpPacket)
  })

  val uop = io.uop

  // when fence.i is commited
  //  1. mark it as a system jump
  //  2. flush the pipeline
  //  3. go to the instruction following fence.i

  val fence_i = uop.valid &&
                (uop.fu_code === s"b$FU_SYS".U) &&
                (uop.sys_code === s"b$SYS_FENCEI".U)
  BoringUtils.addSource(fence_i, "fence_i")

  val in1 = io.in1
  val sys_code = uop.sys_code
  val csr_rw = (sys_code === s"b$SYS_CSRRW".U) ||
               (sys_code === s"b$SYS_CSRRS".U) ||
               (sys_code === s"b$SYS_CSRRC".U)
  val csr_jmp = WireInit(Bool(), false.B)
  val csr_jmp_pc = WireInit(UInt(32.W), 0.U)

  // CSR register definition

  val mhartid   = RegInit(UInt(64.W), 0.U)
  val mstatus   = RegInit(UInt(64.W), "h00001800".U)
  val mie       = RegInit(UInt(64.W), 0.U)
  val mtvec     = RegInit(UInt(64.W), 0.U)
  val mscratch  = RegInit(UInt(64.W), 0.U)
  val mepc      = RegInit(UInt(64.W), 0.U)
  val mcause    = RegInit(UInt(64.W), 0.U)

  // interrupt for mip
  val mtip      = WireInit(UInt(1.W), 0.U)
  BoringUtils.addSink(mtip, "csr_mip_mtip")

  val mcycle    = WireInit(UInt(64.W), 0.U)
  val minstret  = WireInit(UInt(64.W), 0.U)

  BoringUtils.addSink(mcycle, "csr_mcycle")
  BoringUtils.addSink(minstret, "csr_minstret")

  // CSR write function with side effect

  def mstatusWriteFunction(mstatus: UInt): UInt = {
    def get_mstatus_xs(mstatus: UInt): UInt = mstatus(16, 15)
    def get_mstatus_fs(mstatus: UInt): UInt = mstatus(14, 13)
    val mstatus_sd = ((get_mstatus_xs(mstatus) === "b11".U) || (get_mstatus_fs(mstatus) === "b11".U)).asUInt()
    val mstatus_new = Cat(mstatus_sd, mstatus(62, 0))
    mstatus_new
  }

  // ECALL
  when (sys_code === s"b$SYS_ECALL".U) {
    mepc := uop.pc
    mcause := 11.U  // env call from M-mode
    mstatus := Cat(mstatus(63, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
    csr_jmp := true.B
    csr_jmp_pc := Cat(mtvec(31, 2), Fill(2, 0.U))
  }

  // MRET
  when (sys_code === s"b$SYS_MRET".U) {
    mstatus := Cat(mstatus(63, 8), 1.U, mstatus(6, 4), mstatus(7), mstatus(2, 0))
    csr_jmp := true.B
    csr_jmp_pc := mepc(31, 0)
  }

  // CSR register map

  val csr_map = Map(
    MaskedRegMap(Csrs.mhartid , mhartid ),
    MaskedRegMap(Csrs.mstatus , mstatus , "hffffffffffffffff".U, mstatusWriteFunction),
    MaskedRegMap(Csrs.mie     , mie     ),
    MaskedRegMap(Csrs.mtvec   , mtvec   ),
    MaskedRegMap(Csrs.mscratch, mscratch),
    MaskedRegMap(Csrs.mepc    , mepc    ),
    MaskedRegMap(Csrs.mcause  , mcause  ),
    // skip mip
    MaskedRegMap(Csrs.mcycle  , mcycle  ),
    MaskedRegMap(Csrs.minstret, minstret)
  )

  // CSR register read/write

  val addr = uop.inst(31, 20)
  val rdata = WireInit(UInt(64.W), 0.U)
  val wdata = Wire(UInt(64.W))
  val wen = csr_rw

  wdata := MuxLookup(uop.sys_code, 0.U, Array(
    s"b$SYS_CSRRW".U -> in1,
    s"b$SYS_CSRRS".U -> (rdata | in1),
    s"b$SYS_CSRRC".U -> (rdata & ~in1)
  ))

  MaskedRegMap.access(csr_map, addr, rdata, wdata, wen)

  // mip access
  when (Csrs.mip === addr) {
    rdata := 0.U
  }

  io.out := rdata
  io.jmp_packet.jmp := Mux(fence_i, true.B, csr_jmp)
  io.jmp_packet.jmp_pc := Mux(fence_i, uop.pc + 4.U, csr_jmp_pc)

  if (EnableDifftest) {
    val dt_ae = Module(new DifftestArchEvent)
    dt_ae.io.clock          := clock
    dt_ae.io.coreid         := 0.U
    dt_ae.io.intrNO         := 0.U
    dt_ae.io.cause          := 0.U
    dt_ae.io.exceptionPC    := 0.U

    val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U
    dt_cs.io.mstatus        := mstatus
    dt_cs.io.sstatus        := mstatus & "h80000003000de122".U
    dt_cs.io.mepc           := mepc
    dt_cs.io.sepc           := 0.U
    dt_cs.io.mtval          := 0.U
    dt_cs.io.stval          := 0.U
    dt_cs.io.mtvec          := mtvec
    dt_cs.io.stvec          := 0.U
    dt_cs.io.mcause         := mcause
    dt_cs.io.scause         := 0.U
    dt_cs.io.satp           := 0.U
    dt_cs.io.mip            := 0.U
    dt_cs.io.mie            := mie
    dt_cs.io.mscratch       := mscratch
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  }
}
