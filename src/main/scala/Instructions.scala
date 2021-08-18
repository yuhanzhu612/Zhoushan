package zhoushan

import chisel3._
import chisel3.util._

object Instructions {
  def BEQ     = BitPat("b?????????????????000?????1100011")
  def BNE     = BitPat("b?????????????????001?????1100011")
  def BLT     = BitPat("b?????????????????100?????1100011")
  def BGE     = BitPat("b?????????????????101?????1100011")
  def BLTU    = BitPat("b?????????????????110?????1100011")
  def BGEU    = BitPat("b?????????????????111?????1100011")
  def JALR    = BitPat("b?????????????????000?????1100111")
  def JAL     = BitPat("b?????????????????????????1101111")
  def LUI     = BitPat("b?????????????????????????0110111")
  def AUIPC   = BitPat("b?????????????????????????0010111")
  def ADDI    = BitPat("b?????????????????000?????0010011")
  def SLLI    = BitPat("b000000???????????001?????0010011")
  def SLTI    = BitPat("b?????????????????010?????0010011")
  def SLTIU   = BitPat("b?????????????????011?????0010011")
  def XORI    = BitPat("b?????????????????100?????0010011")
  def SRLI    = BitPat("b000000???????????101?????0010011")
  def SRAI    = BitPat("b010000???????????101?????0010011")
  def ORI     = BitPat("b?????????????????110?????0010011")
  def ANDI    = BitPat("b?????????????????111?????0010011")
  def ADD     = BitPat("b0000000??????????000?????0110011")
  def SUB     = BitPat("b0100000??????????000?????0110011")
  def SLL     = BitPat("b0000000??????????001?????0110011")
  def SLT     = BitPat("b0000000??????????010?????0110011")
  def SLTU    = BitPat("b0000000??????????011?????0110011")
  def XOR     = BitPat("b0000000??????????100?????0110011")
  def SRL     = BitPat("b0000000??????????101?????0110011")
  def SRA     = BitPat("b0100000??????????101?????0110011")
  def OR      = BitPat("b0000000??????????110?????0110011")
  def AND     = BitPat("b0000000??????????111?????0110011")
  def ADDIW   = BitPat("b?????????????????000?????0011011")
  def SLLIW   = BitPat("b0000000??????????001?????0011011")
  def SRLIW   = BitPat("b0000000??????????101?????0011011")
  def SRAIW   = BitPat("b0100000??????????101?????0011011")
  def ADDW    = BitPat("b0000000??????????000?????0111011")
  def SUBW    = BitPat("b0100000??????????000?????0111011")
  def SLLW    = BitPat("b0000000??????????001?????0111011")
  def SRLW    = BitPat("b0000000??????????101?????0111011")
  def SRAW    = BitPat("b0100000??????????101?????0111011")
  def LB      = BitPat("b?????????????????000?????0000011")
  def LH      = BitPat("b?????????????????001?????0000011")
  def LW      = BitPat("b?????????????????010?????0000011")
  def LD      = BitPat("b?????????????????011?????0000011")
  def LBU     = BitPat("b?????????????????100?????0000011")
  def LHU     = BitPat("b?????????????????101?????0000011")
  def LWU     = BitPat("b?????????????????110?????0000011")
  def SB      = BitPat("b?????????????????000?????0100011")
  def SH      = BitPat("b?????????????????001?????0100011")
  def SW      = BitPat("b?????????????????010?????0100011")
  def SD      = BitPat("b?????????????????011?????0100011")
  def FENCE   = BitPat("b?????????????????000?????0001111")
  def FENCE_I = BitPat("b?????????????????001?????0001111")
  def ECALL   = BitPat("b00000000000000000000000001110011")
  def EBREAK  = BitPat("b00000000000100000000000001110011")
  def MRET    = BitPat("b00110000001000000000000001110011")
  def WFI     = BitPat("b00010000010100000000000001110011")
  def CSRRW   = BitPat("b?????????????????001?????1110011")
  def CSRRS   = BitPat("b?????????????????010?????1110011")
  def CSRRC   = BitPat("b?????????????????011?????1110011")
  def CSRRWI  = BitPat("b?????????????????101?????1110011")
  def CSRRSI  = BitPat("b?????????????????110?????1110011")
  def CSRRCI  = BitPat("b?????????????????111?????1110011")
  // abstract machine instructions
  def HALT    = BitPat("b00000000000000000000000001101011")
  def PUTCH   = BitPat("b00000000000000000000000001111011")
}

object Csrs {
  val mcycle   = "hb00".U
  val minstret = "hb02".U
}
