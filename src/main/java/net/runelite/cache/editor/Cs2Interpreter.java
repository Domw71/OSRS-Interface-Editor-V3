package net.runelite.cache.editor;

import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.definitions.InterfaceDefinition;
import net.runelite.cache.definitions.ScriptDefinition;

/**
 * Runs an interface group's onLoad CS2 scripts (and their event-hook callbacks) to recover models
 * that the cache leaves unset (modelId 65535) but the client assigns at runtime — the content Displee
 * shows and a static reader misses.
 *
 * <p>DEFENSIVE BY DESIGN: command signatures for this cache's renumbered CS2 dialect are only partly
 * pinned, and a wrong signature desyncs the stack. So each onLoad script is run in isolation; if it
 * underflows or ends unbalanced, its captured effects are DISCARDED. Only models from cleanly-run
 * scripts with an unambiguous (valid component, valid model) pair are applied. The result can only add
 * correct models, never show a wrong one.
 */
public final class Cs2Interpreter
{
	private final MapEditorService service;
	private final int groupId;
	private final int maxModelId;

	/** Solved net stack effects for this cache's CS2 dialect, so most scripts run without desyncing. */
	private final Map<Integer, int[]> sigTable;

	/** child id -> model id, captured from cleanly-run scripts. */
	private final Map<Integer, Integer> models = new HashMap<>();

	/** child id -> 0/1 hidden state set by sethide (opcode 2003), from cleanly-run scripts. */
	private final Map<Integer, Integer> hidden = new HashMap<>();

	public Map<Integer, Integer> hiddenStates()
	{
		return hidden;
	}

	public Cs2Interpreter(MapEditorService service, int groupId, int maxModelId)
	{
		this.service = service;
		this.groupId = groupId;
		this.maxModelId = maxModelId;
		this.sigTable = service.getCs2NetSignatures();
	}

	public Map<Integer, Integer> run(InterfaceDefinition[] group)
	{
		for (InterfaceDefinition d : group)
		{
			if (d == null || d.onLoadListener == null || d.onLoadListener.length == 0)
			{
				continue;
			}
			Object[] l = d.onLoadListener;
			if (!(l[0] instanceof Integer))
			{
				continue;
			}
			int scriptId = (Integer) l[0];
			int[] ia = new int[l.length];
			String[] sa = new String[l.length];
			int ni = 0, ns = 0;
			for (int i = 1; i < l.length; i++)
			{
				if (l[i] instanceof Integer) ia[ni++] = (Integer) l[i];
				else if (l[i] instanceof String) sa[ns++] = (String) l[i];
			}
			ScriptDefinition script = service.getScript(scriptId);
			if (script == null)
			{
				continue;
			}
			// Run this onLoad against a scratch capture; keep only if it ran clean.
			Capture cap = new Capture();
			Cs2Vm vm = new Cs2Vm(service::getScript, cap);
			cap.vm = vm;
			vm.run(script, java.util.Arrays.copyOf(ia, ni), java.util.Arrays.copyOf(sa, ns));
			if (!cap.desynced)
			{
				models.putAll(cap.local);
				hidden.putAll(cap.localHidden);
			}
		}
		return models;
	}

	/** True if a value decodes as a component in this group. */
	private boolean isComp(int v)
	{
		return (v >>> 16) == groupId || (v >= 0 && v < 4096);
	}

	private int childOf(int v)
	{
		return (v >>> 16) == groupId ? (v & 0xFFFF) : v;
	}

	private boolean isModel(int v)
	{
		return v > 0 && v <= maxModelId;
	}

	/** Captures setmodel effects; declares command stack effects; flags desync. */
	private final class Capture implements Cs2Vm.Handler
	{
		Cs2Vm vm;
		boolean desynced;
		final Map<Integer, Integer> local = new HashMap<>();
		final Map<Integer, Integer> localHidden = new HashMap<>();

		// {intArgs, strArgs, intRet, strRet}. Known opcodes are pinned; everything else is derived from
		// the solver's net stack effect (assuming a command either only consumes or only produces on each
		// type, which holds for the setters/queries in onLoad scripts). Unresolved -> desync (discarded).
		private int[] sig(int op)
		{
			switch (op)
			{
				case 2403: case 2404: return new int[]{3, 1, 0, 0}; // hook: (callback, p0, p1) + targetComp str
				case 2003: return new int[]{2, 0, 0, 0};            // sethide(hidden, component)
				case 2108: return new int[]{2, 0, 0, 0};            // setmodel(model, component)
				case 6518: case 6519: return new int[]{0, 0, 1, 0}; // world query -> 0
				default:
				{
					int[] n = sigTable.get(op);
					if (n == null || n[0] == Cs2SigSolver.UNRESOLVED || n[1] == Cs2SigSolver.UNRESOLVED)
					{
						return null;
					}
					return new int[]{Math.max(0, n[0]), Math.max(0, n[1]),
						Math.max(0, -n[0]), Math.max(0, -n[1])};
				}
			}
		}

		public int intArgs(int op) { int[] s = sig(op); if (s == null) { desynced = true; return 0; } return s[0]; }
		public int strArgs(int op) { int[] s = sig(op); return s == null ? 0 : s[1]; }
		public int intReturns(int op) { int[] s = sig(op); return s == null ? 0 : s[2]; }
		public int strReturns(int op) { int[] s = sig(op); return s == null ? 0 : s[3]; }

		public void exec(int op, int[] ints, String[] strs, int[] intOut, String[] strOut)
		{
			// Default any produced values to 0 / "" — we have no live game state, so queries return
			// neutral results. Specific opcodes below override where we can do better.
			for (int i = 0; i < intOut.length; i++) intOut[i] = 0;
			for (int i = 0; i < strOut.length; i++) strOut[i] = "";

			if (op == 2403 || op == 2404)
			{
				// hook: run callback with (p0, p1); target is the last component arg.
				int callback = ints[0];
				int[] params = {ints[1], ints[2]};
				ScriptDefinition cb = service.getScript(callback);
				if (cb != null && cb.getIntArgCount() == params.length)
				{
					vm.run(cb, params, new String[0]);
				}
				return;
			}
			if (op == 2108)
			{
				// setmodel(a, b): one arg is a model, the other a component.
				int a = ints[0], b = ints[1];
				if (isModel(a) && isComp(b)) local.put(childOf(b), a);
				else if (isModel(b) && isComp(a)) local.put(childOf(a), b);
				return;
			}
			if (op == 2003)
			{
				// sethide(hidden, component): first arg is the 0/1 flag, second the component.
				int hide = ints[0], comp = ints[1];
				if (isComp(comp) && (hide == 0 || hide == 1))
				{
					localHidden.put(childOf(comp), hide);
				}
				return;
			}
		}
	}
}
