package net.runelite.cache.editor;

import net.runelite.cache.definitions.ScriptDefinition;

/**
 * A minimal CS2 (ClientScript2) interpreter — enough to run interface onLoad scripts so that
 * script-assigned models / positions / visibility appear in the preview (the content Displee shows
 * and a static reader misses).
 *
 * <p>Core opcodes follow the standard OSRS set (verified against this cache's scripts): 0=push_int,
 * 3=push_string, 6=goto, 7..10/31/32=conditional branches, 21=return, 33/34=push/pop int local,
 * 35/36=push/pop string local, 37=join_string, 38/39=pop discard, 40=gosub_with_params. Commands
 * (opcode >= 100) are dispatched to {@link Handler}, which also declares each command's stack effect
 * so the VM stays balanced. Unknown commands are logged and treated as no-ops with a best guess.
 */
public final class Cs2Vm
{
	/** Receives widget commands and reports each command's stack effect. */
	public interface Handler
	{
		/** How many ints this command pops. -1 = unknown (VM logs and assumes 0). */
		int intArgs(int opcode);

		/** How many strings this command pops. */
		int strArgs(int opcode);

		/** How many ints this command pushes back. */
		int intReturns(int opcode);

		/** How many strings this command pushes back. */
		int strReturns(int opcode);

		/** Apply the command's effect. ints/strs are the popped arguments (in push order). Return
		 *  values to push are written via the returned arrays (may be null when nothing is pushed). */
		void exec(int opcode, int[] ints, String[] strs, int[] intOut, String[] strOut);
	}

	private final java.util.function.IntFunction<ScriptDefinition> scriptSource;
	private final Handler handler;
	private final java.util.Set<Integer> unknownCmds = new java.util.TreeSet<>();
	private int gosubDepth;
	public boolean trace;

	public Cs2Vm(java.util.function.IntFunction<ScriptDefinition> scriptSource, Handler handler)
	{
		this.scriptSource = scriptSource;
		this.handler = handler;
	}

	public java.util.Set<Integer> unknownCommands()
	{
		return unknownCmds;
	}

	/** A proc's leftover operand stack at RETURN — the values passed back to the caller. */
	private static final class Ret
	{
		final int[] ints;
		final String[] strs;

		Ret(int[] ints, String[] strs)
		{
			this.ints = ints;
			this.strs = strs;
		}
	}

	private static final Ret EMPTY = new Ret(new int[0], new String[0]);

	/** Run a script with the given int/string arguments. */
	public void run(ScriptDefinition script, int[] intArgs, String[] strArgs)
	{
		exec2(script, intArgs, strArgs);
	}

	private Ret exec2(ScriptDefinition script, int[] intArgs, String[] strArgs)
	{
		if (script == null || gosubDepth > 64)
		{
			return EMPTY;
		}
		gosubDepth++;
		try
		{
			return exec(script, intArgs, strArgs);
		}
		catch (RuntimeException ex)
		{
			if (trace)
			{
				System.out.println("  VM aborted script " + script.getId() + ": " + ex);
			}
			return EMPTY;
		}
		finally
		{
			gosubDepth--;
		}
	}

	private Ret exec(ScriptDefinition script, int[] intArgs, String[] strArgs)
	{
		int[] ins = script.getInstructions();
		int[] iop = script.getIntOperands();
		String[] sop = script.getStringOperands();

		int[] localInt = new int[Math.max(1, script.getLocalIntCount())];
		String[] localStr = new String[Math.max(1, script.getLocalObjCount())];
		for (int i = 0; i < script.getIntArgCount() && intArgs != null && i < intArgs.length; i++)
		{
			localInt[i] = intArgs[i];
		}
		for (int i = 0; i < script.getObjArgCount() && strArgs != null && i < strArgs.length; i++)
		{
			localStr[i] = strArgs[i];
		}

		int[] iStack = new int[1024];
		String[] sStack = new String[1024];
		int isp = 0, ssp = 0;

		int pc = 0;
		int guard = 0;
		while (pc >= 0 && pc < ins.length)
		{
			if (++guard > 200000)
			{
				break; // runaway guard
			}
			int op = ins[pc];
			int a = iop[pc];
			switch (op)
			{
				case 0: // push_int
					iStack[isp++] = a;
					break;
				case 3: // push_string
					sStack[ssp++] = sop[pc];
					break;
				case 25: // push_varbit — no live game state, assume default 0
				case 42: // push_varc_int — default 0
					iStack[isp++] = 0;
					break;
				case 33: // push_int_local
					iStack[isp++] = localInt[a];
					break;
				case 34: // pop_int_local
					localInt[a] = iStack[--isp];
					break;
				case 35: // push_string_local
					sStack[ssp++] = localStr[a];
					break;
				case 36: // pop_string_local
					localStr[a] = sStack[--ssp];
					break;
				case 37: // join_string (concat a strings)
				{
					StringBuilder sb = new StringBuilder();
					String[] parts = new String[a];
					for (int i = a - 1; i >= 0; i--)
					{
						parts[i] = sStack[--ssp];
					}
					for (String p : parts)
					{
						sb.append(p == null ? "" : p);
					}
					sStack[ssp++] = sb.toString();
					break;
				}
				case 38: // pop_int_discard
					isp--;
					break;
				case 39: // pop_string_discard
					ssp--;
					break;
				case 6: // goto
					pc += a;
					break;
				case 7: // branch_not
					isp -= 2;
					if (iStack[isp] != iStack[isp + 1]) pc += a;
					break;
				case 8: // branch_equals
					isp -= 2;
					if (iStack[isp] == iStack[isp + 1]) pc += a;
					break;
				case 9: // branch_less_than
					isp -= 2;
					if (iStack[isp] < iStack[isp + 1]) pc += a;
					break;
				case 10: // branch_greater_than
					isp -= 2;
					if (iStack[isp] > iStack[isp + 1]) pc += a;
					break;
				case 31: // branch_greater_than_or_equals
					isp -= 2;
					if (iStack[isp] >= iStack[isp + 1]) pc += a;
					break;
				case 32: // branch_less_than_or_equals
					isp -= 2;
					if (iStack[isp] <= iStack[isp + 1]) pc += a;
					break;
				case 21: // return — leftover operand stack is the return value(s)
					return new Ret(java.util.Arrays.copyOf(iStack, isp), java.util.Arrays.copyOf(sStack, ssp));
				case 40: // gosub_with_params (call proc `a`)
				{
					ScriptDefinition proc = scriptSource.apply(a);
					if (proc != null)
					{
						int ia = proc.getIntArgCount(), sa = proc.getObjArgCount();
						int[] pi = new int[ia];
						String[] ps = new String[sa];
						for (int i = ia - 1; i >= 0; i--) pi[i] = iStack[--isp];
						for (int i = sa - 1; i >= 0; i--) ps[i] = sStack[--ssp];
						Ret r = exec2(proc, pi, ps);
						for (int v : r.ints) iStack[isp++] = v;
						for (String v : r.strs) sStack[ssp++] = v;
					}
					break;
				}
				default:
				{
					if (op < 100)
					{
						// Unhandled core opcode — skip, but note it.
						if (trace) System.out.println("  ? core op " + op + " @" + pc);
						break;
					}
					// Command (widget op etc.)
					if (trace)
					{
						System.out.println("  @" + pc + " CMD " + op + "  before: iDepth=" + isp + " sDepth=" + ssp);
					}
					int ni = handler.intArgs(op);
					int ns = handler.strArgs(op);
					if (ni < 0)
					{
						unknownCmds.add(op);
						ni = 0;
						ns = 0;
					}
					int[] ints = new int[ni];
					String[] strs = new String[ns];
					for (int i = ni - 1; i >= 0; i--) ints[i] = iStack[--isp];
					for (int i = ns - 1; i >= 0; i--) strs[i] = sStack[--ssp];
					int nir = handler.intReturns(op), nsr = handler.strReturns(op);
					int[] intOut = new int[Math.max(0, nir)];
					String[] strOut = new String[Math.max(0, nsr)];
					handler.exec(op, ints, strs, intOut, strOut);
					for (int i = 0; i < nir; i++) iStack[isp++] = intOut[i];
					for (int i = 0; i < nsr; i++) sStack[ssp++] = strOut[i];
					break;
				}
			}
			// Branch/goto operands are relative to the instruction AFTER the branch, so every opcode
			// (branch or not) advances by one here — the branch cases above only added their delta.
			pc++;
			if (isp < 0 || ssp < 0)
			{
				if (trace) System.out.println("  stack underflow @" + pc + " op " + op);
				return EMPTY;
			}
		}
		return new Ret(java.util.Arrays.copyOf(iStack, Math.max(0, isp)), java.util.Arrays.copyOf(sStack, Math.max(0, ssp)));
	}
}
