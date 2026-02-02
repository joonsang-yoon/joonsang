// Exhaustive test for Radix4BoothMultiplier(dataWidth = 15, useMetadata =
// false) Drives all combinations of (isLhsSigned, isRhsSigned, in1, in2) =>
// 2^32 tests. You can set env MAX_TESTS to limit the run for quick sanity
// checks. Build/run is managed by HardIntTester.scala.

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include <deque>
#include <limits>

#include "dut.h"  // verilator --prefix=dut

#if VM_TRACE
#include "verilator.h"
#endif

// Optional progress verbosity
static bool verbose = true;

static inline void tick(dut& m, uint64_t& cycle
#if VM_TRACE
                        ,
                        VerilatedVcdC* tfp
#endif
) {
  // falling edge
  m.clock = 0;
  m.eval();
#if VM_TRACE
  if (tfp) tfp->dump(static_cast<vluint64_t>(cycle * 2));
#endif

  // rising edge
  m.clock = 1;
  m.eval();
#if VM_TRACE
  if (tfp) tfp->dump(static_cast<vluint64_t>(cycle * 2 + 1));
#endif

  cycle++;
}

namespace {
constexpr unsigned W = 15;
constexpr uint32_t MASK = (1u << W) - 1u;
constexpr uint32_t SIGN = (1u << (W - 1));
constexpr uint32_t LIM = (1u << W);
constexpr uint64_t MASK2W = (1ULL << (2 * W)) - 1ULL;

static inline int64_t sextN(uint32_t x) {
  int32_t s = static_cast<int32_t>(x & MASK);
  if (s & SIGN) s -= static_cast<int32_t>(1u << W);
  return static_cast<int64_t>(s);
}

static inline uint64_t zextN(uint32_t x) {
  return static_cast<uint64_t>(x & MASK);
}

static inline uint32_t pack2N(int64_t p) {
  return static_cast<uint32_t>(static_cast<uint64_t>(p) & MASK2W);
}

static inline void compute_expected(uint32_t in1, uint32_t in2,
                                    bool isLhsSigned, bool isRhsSigned,
                                    uint32_t& prod2W) {
  int64_t a = isLhsSigned ? sextN(in1) : static_cast<int64_t>(zextN(in1));
  int64_t b = isRhsSigned ? sextN(in2) : static_cast<int64_t>(zextN(in2));
  int64_t p = a * b;  // fits comfortably in int64 for W <= 16
  prod2W = pack2N(p);
}

struct Expect {
  uint32_t prod2W;
  uint8_t isLhsSigned;
  uint8_t isRhsSigned;
  uint16_t in1, in2;
};
}  // namespace

int main(int argc, char* argv[]) {
  dut module;
  uint64_t cycle = 0;

#if VM_TRACE
  VerilatedVcdFILE vcdfd(stderr);
  VerilatedVcdC tfp(&vcdfd);
  Verilated::traceEverOn(true);
  module.trace(&tfp, 99);
  tfp.open("");
  VerilatedVcdC* tfp_ptr = &tfp;
#else
  void* tfp_ptr = nullptr;
#endif

  // Reset
  module.reset = 1;
  module.io_req_valid = 0;
  module.io_resp_ready = 0;
  for (int i = 0; i < 10; i++) {
    tick(module, cycle
#if VM_TRACE
         ,
         &tfp
#endif
    );
  }
  module.reset = 0;

  // Always ready to consume responses
  module.io_resp_ready = 1;
  module.eval();

  // Exhaustive enumeration state
  uint32_t isLhsSigned = 0;
  uint32_t isRhsSigned = 0;
  uint32_t in1 = 0;
  uint32_t in2 = 0;

  // MAX_TESTS escape hatch
  uint64_t max_tests = std::numeric_limits<uint64_t>::max();
  if (const char* mt = getenv("MAX_TESTS")) {
    max_tests = strtoull(mt, nullptr, 10);
    if (max_tests == 0) max_tests = 1;
  }

  // Work queue for expected results (module is pipelined)
  std::deque<Expect> expQ;

  uint64_t issued = 0;   // number of requests sent
  uint64_t checked = 0;  // number of responses checked
  uint64_t errors = 0;

  // Helper to advance enumeration (isLhsSigned x isRhsSigned x in1 x in2)
  auto advance = [&]() {
    if (++in2 < LIM) return true;
    in2 = 0;
    if (++in1 < LIM) return true;
    in1 = 0;
    if (++isRhsSigned < 2u) return true;
    isRhsSigned = 0;
    if (++isLhsSigned < 2u) return true;
    return false;  // done
  };

  bool done_issuing = false;

  // Prime first input
  module.io_req_bits_isLhsSigned = isLhsSigned & 1u;
  module.io_req_bits_isRhsSigned = isRhsSigned & 1u;
  module.io_req_bits_in1 = in1 & MASK;
  module.io_req_bits_in2 = in2 & MASK;

  while (!done_issuing || !expQ.empty()) {
    // Drive valid when we still have inputs to send and under MAX_TESTS
    bool can_issue_more = !done_issuing && (issued < max_tests);
    module.io_req_valid = can_issue_more ? 1 : 0;

    // Pre-sample fire on req (combinational ready + our valid)
    bool will_fire_req = module.io_req_valid && module.io_req_ready;

    // Tick
    tick(module, cycle
#if VM_TRACE
         ,
         &tfp
#endif
    );

    // If request fired, compute and enqueue expected, and advance inputs
    if (will_fire_req) {
      uint32_t exp_p = 0;
      compute_expected(in1, in2, (isLhsSigned & 1u) != 0,
                       (isRhsSigned & 1u) != 0, exp_p);
      expQ.push_back(Expect{exp_p, static_cast<uint8_t>(isLhsSigned & 1u),
                            static_cast<uint8_t>(isRhsSigned & 1u),
                            static_cast<uint16_t>(in1 & MASK),
                            static_cast<uint16_t>(in2 & MASK)});
      issued++;

      // Advance to next combo or finish issuing
      if (!advance() || (issued >= max_tests)) {
        done_issuing = true;
      }

      // Update next input pins
      module.io_req_bits_isLhsSigned = isLhsSigned & 1u;
      module.io_req_bits_isRhsSigned = isRhsSigned & 1u;
      module.io_req_bits_in1 = in1 & MASK;
      module.io_req_bits_in2 = in2 & MASK;
    }

    // Check response if valid
    if (module.io_resp_valid && module.io_resp_ready) {
      if (expQ.empty()) {
        fprintf(stderr,
                "Internal error: response with empty expectation queue.\n");
        errors++;
        break;
      }
      Expect e = expQ.front();
      expQ.pop_front();

      uint32_t got_p =
          static_cast<uint32_t>(module.io_resp_bits_product & MASK2W);

      if (got_p != e.prod2W) {
        errors++;
        fprintf(stderr,
                "[%#012llx] ERROR isLhsSigned=%u isRhsSigned=%u in1=%#06x "
                "in2=%#06x -> got p=%#010x, expected p=%#010x\n",
                (unsigned long long)checked, e.isLhsSigned, e.isRhsSigned,
                e.in1, e.in2, got_p, e.prod2W);
        if (errors >= 20) {
          fprintf(stderr, "Reached %llu errors. Aborting.\n",
                  (unsigned long long)errors);
          break;
        }
      }

      checked++;
      if (verbose && ((checked & ((1ULL << 20) - 1)) == 0ULL)) {
        printf("Checked %#llx tests...\n", (unsigned long long)checked);
        fflush(stdout);
      }
    }
  }

  printf("Ran %llu tests.\n", (unsigned long long)checked);
  if (errors == 0) {
    fputs("No errors found.\n", stdout);
  }

#if VM_TRACE
  tfp.close();
#endif
  return errors ? 1 : 0;
}
