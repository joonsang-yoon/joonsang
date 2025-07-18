Module 'divSqrtRecFN_small' computes a division or square root for standard
floating-point in recoded form, at the rate of one cycle per significand
bit.

------------------------------------------------------------------------------
Function interface

Module 'divSqrtRecFN_small' performs only one division or square root at a
time, except that the last cycle of the previous operation can overlap with
the first cycle of the next one.  A new operation can be started in any
clock cycle for which the output 'inReady' is true.  An operation is started
in any clock cycle for which 'inValid && inReady' is true.  The operation
will be a division if 'sqrtOp' is false and a square root if 'sqrtOp' is
true.

When a division is started, the values of inputs 'a', 'b', and
'roundingMode' in the same clock cycle are interpreted as the dividend,
divisor, and rounding mode, respectively.  (The division is thus 'a'/'b'.)
For a square root, 'a' provides the function argument, and 'roundingMode'
is again the rounding mode.  Arguments 'a' and 'b' are supplied in recoded
form, which is documented elsewhere.

Input 'detectTininess' specifies whether tininess is to be detected before
or after rounding.  This input must be held constant while an operation
is being computed.  (Usually 'detectTininess' is permanently tied true or
false.)

In each clock cycle, input 'inValid' is expected to be stable very early
in the cycle.  When 'inValid' is asserted, all other inputs are expected to
be stable very early in the cycle as well.  Once an operation has started,
inputs other than 'detectTininess' do not need to be held constant while the
operation is being computed.

When a division result is ready, 'outValid_div' is true, and when a square
root result is ready, 'outValid_sqrt' is true.  In either case, 'out' is
the function result in recoded form (same as the 'a' and 'b' inputs), and
'exceptionFlags' is a bit vector of the exception flags, with this format:

    { invalid, infinity, overflow, underflow, inexact }

(The "infinity" exception is also known as "divide-by-zero".)

Outputs 'out' and 'exceptionFlags' are expected to be stable only late in a
clock cycle.