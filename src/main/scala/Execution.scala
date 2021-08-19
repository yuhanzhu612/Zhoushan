package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

trait Ext {
  def SignExt32_64(x: UInt) : UInt = Cat(Fill(32, x(31)), x)
  def ZeroExt32_64(x: UInt) : UInt = Cat(Fill(32, 0.U), x)
}

class Execution extends Module with Ext {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val rs1_data = Input(UInt(64.W))
    val rs2_data = Input(UInt(64.W))
    val uop_out = Output(new MicroOp())
    val result = Output(UInt(64.W))
    val busy = Output(Bool())
    val jmp = Output(Bool())
    val jmp_pc = Output(UInt(32.W))
    val dmem = Flipped(new RamIO)
  })

  val uop = io.uop
  val in1_0, in1, in2_0, in2 = Wire(UInt(64.W))

  in1_0 := MuxLookup(uop.rs1_src, 0.U, Array(
    RS_FROM_RF  -> io.rs1_data,
    RS_FROM_IMM -> SignExt32_64(uop.imm),
    RS_FROM_PC  -> ZeroExt32_64(uop.pc),
    RS_FROM_NPC -> ZeroExt32_64(uop.npc)
  )).asUInt()

  in2_0 := MuxLookup(uop.rs2_src, 0.U, Array(
    RS_FROM_RF  -> io.rs2_data,
    RS_FROM_IMM -> SignExt32_64(uop.imm),
    RS_FROM_PC  -> ZeroExt32_64(uop.pc),
    RS_FROM_NPC -> ZeroExt32_64(uop.npc)
  )).asUInt()

  in1 := Mux(uop.w_type, Mux(uop.alu_code === ALU_SRL, ZeroExt32_64(in1_0(31, 0)), SignExt32_64(in1_0(31, 0))), in1_0)
  in2 := Mux(uop.w_type, SignExt32_64(in2_0(31, 0)), in2_0)

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  val lsu = Module(new Lsu)
  lsu.io.uop := uop
  lsu.io.in1 := in1
  lsu.io.in2 := in2
  lsu.io.dmem <> io.dmem

  val csr = Module(new Csr)
  csr.io.uop := uop
  csr.io.in1 := in1

  val busy = lsu.io.busy

  io.uop_out := io.uop
  io.result := alu.io.out | lsu.io.out | csr.io.out
  io.busy := busy
  io.jmp := alu.io.jmp
  io.jmp_pc := alu.io.jmp_pc
}
