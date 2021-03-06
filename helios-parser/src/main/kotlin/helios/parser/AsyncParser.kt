package helios.parser

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import java.nio.ByteBuffer

sealed class Mode(val start: Int, val value: Int)
object UnwrapArray : Mode(-5, 1)
object ValueStream : Mode(-1, 0)
object SingleValue : Mode(-1, -1)

/**
 * AsyncParser is able to parse chunks of data (encoded as
 * Option[ByteBuffer] instances) and parse asynchronously. You can
 * use the factory methods in the companion object to instantiate an
 * async parser.
 *
 * The async parser's fields are described below:
 *
 * The (state, curr, stack) triple is used to save and restore parser
 * state between async calls. State also helps encode extra
 * information when streaming or unwrapping an array.
 *
 * The (data, len, allocated) triple is used to manage the underlying
 * data the parser is keeping track of. As new data comes in, data may
 * be expanded if not enough space is available.
 *
 * The offset parameter is used to drive the outer async parsing. It
 * stores similar information to curr but is kept separate to avoid
 * "corrupting" our snapshot.
 *
 * The done parameter is used internally to help figure out when the
 * atEof() parser method should return true. This will be set when
 * apply(None) is called.
 *
 * The streamMode parameter controls how the asynchronous parser will
 * be handling multiple values. There are three states:
 *
 *    1: An array is being unwrapped. Normal JSON array rules apply
 *       (Note that if the outer value observed is not an array, this
 *       mode will toggle to the -1 mode).
 *
 *    0: A stream of individual JSON elements separated by whitespace
 *       are being parsed. We can return each complete element as we
 *       parse it.
 *
 *   -1: No streaming is occuring. Only a single JSON value is
 *       allowed.
 */
class AsyncParser<J>(
        private var state: Int,
        private var curr: Int,
        private var stack: List<FContext<J>>,
        private var data: ByteArray,
        private var len: Int,
        private var allocated: Int,
        private var offset: Int,
        private var done: Boolean,
        private var streamMode: Int) : ByteBasedParser<J> {

    companion object {
        operator fun <J> invoke(mode: Mode = SingleValue): AsyncParser<J> =
                AsyncParser(state = mode.start, curr = 0, stack = emptyList(),
                        data = ByteArray(131072), len = 0, allocated = 131072,
                        offset = 0, done = false, streamMode = mode.value)
    }

    private var line = 0
    private var pos = 0

    override fun line(): Int = line

    override fun newline(i: Int) {
        line += 1; pos = i + 1
    }

    override fun column(i: Int) = i - pos

    fun copy() =
            AsyncParser(state, curr, stack, data.clone(), len, allocated, offset, done, streamMode)

    fun absorb(buf: ByteBuffer, facade: Facade<J>): Either<ParseException, List<J>> {
        done = false
        val buflen = buf.limit() - buf.position()
        val need = len + buflen
        resizeIfNecessary(need)
        buf.get(data, len, buflen)
        len = need
        return churn(facade)
    }

    fun absorb(bytes: ByteArray, facade: Facade<J>): Either<ParseException, List<J>> =
            absorb(ByteBuffer.wrap(bytes), facade)

    fun absorb(s: String, facade: Facade<J>): Either<ParseException, List<J>> =
            absorb(ByteBuffer.wrap(s.toByteArray(utf8)), facade)

    fun finish(facade: Facade<J>): Either<ParseException, List<J>> {
        done = true
        return churn(facade)
    }

    fun resizeIfNecessary(need: Int): Unit {
        // if we don't have enough free space available we'll need to grow our
        // data array. we never shrink the data array, assuming users will call
        // feed with similarly-sized buffers.
        if (need > allocated) {
            val doubled = if (allocated < 0x40000000) allocated * 2 else Int.MAX_VALUE
            val newsize = Math.max(need, doubled)
            val newdata = ByteArray(newsize)
            System.arraycopy(data, 0, newdata, 0, len)
            data = newdata
            allocated = newsize
        }
    }

    /**
     * Explanation of the new synthetic states. The parser machinery
     * uses positive integers for states while parsing json values. We
     * use these negative states to keep track of the async parser's
     * status between json values.
     *
     * ASYNC_PRESTART: We haven't seen any non-whitespace yet. We
     * could be parsing an array, or not. We are waiting for valid
     * JSON.
     *
     * ASYNC_START: We've seen an array and have begun unwrapping
     * it. We could see a ] if the array is empty, or valid JSON.
     *
     * ASYNC_END: We've parsed an array and seen the final ]. At this
     * point we should only see whitespace or an EOF.
     *
     * ASYNC_POSTVAL: We just parsed a value from inside the array. We
     * expect to see whitespace, a comma, or a ].
     *
     * ASYNC_PREVAL: We are in an array and we just saw a comma. We
     * expect to see whitespace or a JSON value.
     */
    val ASYNC_PRESTART = -5
    val ASYNC_START = -4
    val ASYNC_END = -3
    val ASYNC_POSTVAL = -2
    val ASYNC_PREVAL = -1

    fun churn(facade: Facade<J>): Either<ParseException, List<J>> {

        // accumulates json values
        val results = arrayListOf<J>()

        // we rely on exceptions to tell us when we run out of data
        return try {
            while (true) {
                if (state < 0) {
                    when (at(offset)) {
                        '\n' -> {
                            newline(offset)
                            offset += 1
                        }

                        ' ', '\t', '\r' -> offset += 1
                        '[' -> {
                            if (state == ASYNC_PRESTART) {
                                offset += 1
                                state = ASYNC_START
                            } else if (state == ASYNC_END) {
                                die(offset, "expected eof")
                            } else if (state == ASYNC_POSTVAL) {
                                die(offset, "expected , or ]")
                            } else {
                                state = 0
                            }
                        }

                        ',' -> {
                            if (state == ASYNC_POSTVAL) {
                                offset += 1
                                state = ASYNC_PREVAL
                            } else if (state == ASYNC_END) {
                                die(offset, "expected eof")
                            } else {
                                die(offset, "expected json value")
                            }
                        }

                        ']' -> {
                            if (state == ASYNC_POSTVAL || state == ASYNC_START) {
                                if (streamMode > 0) {
                                    offset += 1
                                    state = ASYNC_END
                                } else {
                                    die(offset, "expected json value or eof")
                                }
                            } else if (state == ASYNC_END) {
                                die(offset, "expected eof")
                            } else {
                                die(offset, "expected json value")
                            }
                        }

                        else -> {
                            if (state == ASYNC_END) {
                                die(offset, "expected eof")
                            } else if (state == ASYNC_POSTVAL) {
                                die(offset, "expected ] or ,")
                            } else {
                                if (state == ASYNC_PRESTART && streamMode > 0) streamMode = -1
                                state = 0
                            }
                        }
                    }

                } else {
                    // jump straight back into rparse
                    offset = reset(offset)
                    val (value, j) = if (state <= 0) {
                        parse(offset, facade)
                    } else {
                        rparse(state, curr, stack, facade)
                    }
                    if (streamMode > 0) {
                        state = ASYNC_POSTVAL
                    } else if (streamMode == 0) {
                        state = ASYNC_PREVAL
                    } else {
                        state = ASYNC_END
                    }
                    curr = j
                    offset = j
                    stack = emptyList()
                    results.add(value)
                }
            }
            Right(results)
        } catch (e: AsyncException) {
            if (done) {
                // if we are done, make sure we ended at a good stopping point
                if (state == ASYNC_PREVAL || state == ASYNC_END) Right(results)
                else Left(ParseException("exhausted input", -1, -1, -1))
            } else {
                // we ran out of data, so return what we have so far
                Right(results)
            }
        } catch (e: ParseException) {
            // we hit a parser error, so return that error and results so far
            Left(e)
        }
    }

    // every 1M we shift our array back by 1M.
    override fun reset(i: Int): Int = if (offset >= 1048576) {
        len -= 1048576
        offset -= 1048576
        pos -= 1048576
        System.arraycopy(data, 1048576, data, 0, len)
        i - 1048576
    } else {
        i
    }

    /**
     * We use this to keep track of the last recoverable place we've
     * seen. If we hit an AsyncException, we can later resume from this
     * point.
     *
     * This method is called during every loop of rparse, and the
     * arguments are the exact arguments we can pass to rparse to
     * continue where we left off.
     */
    override fun checkpoint(state: Int, i: Int, stack: List<FContext<J>>) {
        this.state = state
        this.curr = i
        this.stack = stack
    }

    /**
     * This is a specialized accessor for the case where our underlying data are
     * bytes not chars.
     */
    override fun byte(i: Int): Byte =
            if (i >= len) throw AsyncException() else data[i]

    // we need to signal if we got out-of-bounds
    override fun at(i: Int): Char =
            if (i >= len) throw AsyncException() else data[i].toChar()

    /**
     * Access a byte range as a string.
     *
     * Since the underlying data are UTF-8 encoded, i and k must occur on unicode
     * boundaries. Also, the resulting String is not guaranteed to have length
     * (k - i).
     */
    override fun at(i: Int, k: Int): CharSequence {
        if (k > len) throw AsyncException()
        val size = k - i
        val arr = ByteArray(size)
        System.arraycopy(data, i, arr, 0, size)
        return String(arr, utf8)
    }

    // the basic idea is that we don't signal EOF until done is true, which means
    // the client explicitly send us an EOF.
    override fun atEof(i: Int): Boolean =
            if (done) i >= len else false

    // we don't have to do anything special on close.
    override fun close(): Unit = Unit
}

/**
 * This class is used internally by AsyncParser to signal that we've
 * reached the end of the particular input we were given.
 */
private class AsyncException : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * This is a more prosaic exception which indicates that we've hit a
 * parsing error.
 */
private class FailureException : Exception()
