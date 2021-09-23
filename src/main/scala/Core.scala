package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._

class Core extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val imem = new CoreBusIO
    val dmem = new CoreBusIO
  })

  val flush = WireInit(false.B)

  /* ----- Stage 1 - Instruction Fetch (IF) ------ */

  val fetch = Module(new InstFetch)

  val icache = Module(new Cache(id = InstCacheId))
  icache.io.in <> fetch.io.imem
  icache.io.out <> io.imem

  /* ----- Stage 2 - Instruction Buffer (IB) ----- */

  val ibuf = Module(new InstBuffer)
  ibuf.io.in <> fetch.io.out
  ibuf.io.flush := flush

  /* ----- Stage 3 - Instruction Decode (ID) ----- */

  val decode = Module(new Decode)
  decode.io.in <> ibuf.io.out
  decode.io.flush := flush

  val rename = Module(new Rename)
  rename.io.in <> decode.io.out
  rename.io.flush := flush

  /* ----- Stage 4 - Issue (IS) ------------------ */

  val stall_reg = Module(new StallRegister)
  stall_reg.io.in <> rename.io.out
  stall_reg.io.flush := flush

  val rob = Module(new Rob)
  val isu = Module(new IssueUnit)

  rob.io.in.bits := stall_reg.io.out.bits
  rob.io.in.valid := stall_reg.io.out.valid && isu.io.in.ready
  rob.io.flush := flush

  rename.io.cm_recover := RegNext(rob.io.jmp_packet.mis)
  rename.io.cm := rob.io.cm

  fetch.io.jmp_packet := rob.io.jmp_packet
  flush := rob.io.jmp_packet.mis

  isu.io.in.bits := stall_reg.io.out.bits
  isu.io.in.valid := stall_reg.io.out.valid && rob.io.in.ready
  isu.io.rob_addr := rob.io.rob_addr
  isu.io.flush := flush
  isu.io.avail_list := rename.io.avail_list
  isu.io.csr_ready := rob.io.csr_ready

  stall_reg.io.out.ready := rob.io.in.ready && isu.io.in.ready

  /* ----- Stage 5 - Register File (RF) ---------- */

  val prf = Module(new Prf)
  prf.io.in := isu.io.out
  prf.io.flush := flush

  /* ----- Stage 6 - Execution (EX) -------------- */

  val execution = Module(new Execution)
  execution.io.in <> prf.io.out
  execution.io.flush := flush
  execution.io.rs1_data := prf.io.rs1_data
  execution.io.rs2_data := prf.io.rs2_data

  rename.io.exe := execution.io.out

  rob.io.exe := execution.io.out
  rob.io.exe_ecp := execution.io.out_ecp

  isu.io.lsu_ready := execution.io.lsu_ready

  val sq = Module(new StoreQueue)
  sq.io.flush := flush
  sq.io.in <> execution.io.dmem
  sq.io.deq_req := rob.io.sq_deq_req

  val crossbar2to1 = Module(new CacheBusCrossbar2to1)
  crossbar2to1.io.in(0) <> sq.io.out_st
  crossbar2to1.io.in(1) <> sq.io.out_ld

  val crossbar1to2 = Module(new CacheBusCrossbar1to2)
  crossbar1to2.io.in <> crossbar2to1.io.out

  val dcache = Module(new Cache(DataCacheId))
  dcache.io.in <> crossbar1to2.io.out(0)
  dcache.io.out <> io.dmem

  val clint = Module(new Clint)
  clint.io.in <> crossbar1to2.io.out(1)

  /* ----- Stage 7 - Commit (CM) ----------------- */

  prf.io.rd_en := execution.io.rd_en
  prf.io.rd_paddr := execution.io.rd_paddr
  prf.io.rd_data := execution.io.rd_data

  val cm = rob.io.cm
  val cm_rd_data = rob.io.cm_rd_data

  /* ----- Difftest ------------------------------ */

  val rf_a0 = WireInit(0.U(64.W))
  BoringUtils.addSink(rf_a0, "rf_a0")

  if (EnableDifftest) {
    for (i <- 0 until CommitWidth) {
      val skip = (cm(i).inst === Instructions.PUTCH) ||
                 (cm(i).fu_code === Constant.FU_CSR && cm(i).inst(31, 20) === Csrs.mcycle)

      val dt_ic = Module(new DifftestInstrCommit)
      dt_ic.io.clock    := clock
      dt_ic.io.coreid   := 0.U
      dt_ic.io.index    := i.U
      dt_ic.io.valid    := RegNext(cm(i).valid)
      dt_ic.io.pc       := RegNext(cm(i).pc)
      dt_ic.io.instr    := RegNext(cm(i).inst)
      dt_ic.io.skip     := RegNext(skip)
      dt_ic.io.isRVC    := false.B
      dt_ic.io.scFailed := false.B
      dt_ic.io.wen      := RegNext(cm(i).rd_en)
      dt_ic.io.wdata    := RegNext(cm_rd_data(i))
      dt_ic.io.wdest    := RegNext(cm(i).rd_addr)

      when (dt_ic.io.valid && dt_ic.io.instr === Instructions.PUTCH) {
        printf("%c", rf_a0(7, 0))
      }

      if (DebugCommit) {
        val u = cm(i)
        when (u.valid) {
          printf("%d: [CM %d ] pc=%x inst=%x rs1=%d->%d rs2=%d->%d rd(en=%x)=%d->%d bp(br=%x bpc=%x) rob=%d\n", DebugTimer(), i.U, 
                 u.pc, u.inst, u.rs1_addr, u.rs1_paddr, u.rs2_addr, u.rs2_paddr, u.rd_en, u.rd_addr, u.rd_paddr, u.pred_br, u.pred_bpc, u.rob_addr)
        }
      }
    }
  }

  val cycle_cnt = RegInit(0.U(64.W))
  val instr_cnt = RegInit(0.U(64.W))

  BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  BoringUtils.addSource(instr_cnt, "csr_minstret")

  cycle_cnt := cycle_cnt + 1.U
  instr_cnt := instr_cnt + PopCount(cm.map(_.valid))

  if (EnableDifftest) {
    val trap = Cat(cm.map(_.inst === "h0000006b".U).reverse) & Cat(cm.map(_.valid).reverse)
    val trap_idx = OHToUInt(trap)

    val dt_te = Module(new DifftestTrapEvent)
    dt_te.io.clock    := clock
    dt_te.io.coreid   := 0.U
    dt_te.io.valid    := RegNext(trap.orR)
    dt_te.io.code     := RegNext(rf_a0(2, 0))
    dt_te.io.pc       := 0.U
    for (i <- 0 until CommitWidth) {
      when (trap_idx === i.U) {
        dt_te.io.pc   := RegNext(cm(i).pc)
      }
    }
    dt_te.io.cycleCnt := cycle_cnt
    dt_te.io.instrCnt := instr_cnt

    if (EnableMisRateCounter) {
      val profile_jmp_counter = WireInit(UInt(64.W), 0.U)
      val profile_mis_counter = WireInit(UInt(64.W), 0.U)
      BoringUtils.addSink(profile_jmp_counter, "profile_jmp_counter")
      BoringUtils.addSink(profile_mis_counter, "profile_mis_counter")
      when (dt_te.io.valid) {
        printf("Jump: %d, Mis: %d\n", profile_jmp_counter, profile_mis_counter)
      }
    }
  }

  if (EnableDifftest) {
    val dt_ae = Module(new DifftestArchEvent)
    dt_ae.io.clock        := clock
    dt_ae.io.coreid       := 0.U
    dt_ae.io.intrNO       := 0.U
    dt_ae.io.cause        := 0.U
    dt_ae.io.exceptionPC  := 0.U

    val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U  // Machine mode
    dt_cs.io.mstatus        := 0.U
    dt_cs.io.sstatus        := 0.U
    dt_cs.io.mepc           := 0.U
    dt_cs.io.sepc           := 0.U
    dt_cs.io.mtval          := 0.U
    dt_cs.io.stval          := 0.U
    dt_cs.io.mtvec          := 0.U
    dt_cs.io.stvec          := 0.U
    dt_cs.io.mcause         := 0.U
    dt_cs.io.scause         := 0.U
    dt_cs.io.satp           := 0.U
    dt_cs.io.mip            := 0.U
    dt_cs.io.mie            := 0.U
    dt_cs.io.mscratch       := 0.U
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  }
}
