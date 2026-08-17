package net.runelite.cache.editor;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.definitions.InterfaceDefinition;
import net.runelite.cache.definitions.ScriptDefinition;

/**
 * Solve CS2 command stack signatures for this cache's renumbered dialect, without a reference table.
 *
 * <p>Method: a branch-free script must have a balanced operand stack at RETURN, so
 * (explicit int pushes) - Σ netInt(command) = 0 and likewise for strings. Each branch-free script is
 * one linear equation over the unknown per-command net effects; with enough scripts the system is
 * determined. We seed known opcodes and iteratively resolve any equation with a single unknown.
 *
 * <p>Prints the solved net int/string effect (pops - pushes) per command opcode.
 */
public final class Cs2SigSolver
{
	// Core opcode net effects (int, string). Push ops add to a stack (net -1 as a "producer"); this is
	// the effect on stack depth of executing the op (final depth delta).
	static int[] coreNet(int op, int operand)
	{
		switch (op)
		{
			case 0: return new int[]{+1, 0};      // push_int
			case 25: case 42: return new int[]{+1, 0}; // push varbit/varc -> int
			case 33: return new int[]{+1, 0};     // push_int_local
			case 34: return new int[]{-1, 0};     // pop_int_local
			case 3: return new int[]{0, +1};      // push_string
			case 35: return new int[]{0, +1};     // push_string_local
			case 36: return new int[]{0, -1};     // pop_string_local
			case 37: return new int[]{0, 1 - operand}; // join_string: pop `operand`, push 1
			case 38: return new int[]{-1, 0};     // pop_int_discard
			case 39: return new int[]{0, -1};     // pop_string_discard
			default: return null;                 // branch/return/gosub/command handled elsewhere
		}
	}

	static boolean isBranch(int op)
	{
		return op == 6 || op == 7 || op == 8 || op == 9 || op == 10 || op == 31 || op == 32;
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
		MapEditorService service = new MapEditorService(new File(args[0]), new JsonXteaKeyProvider(null));
		service.open();

		List<ScriptDefinition> corpus = buildCorpus(service);
		System.out.println("corpus: " + corpus.size() + " scripts");
		Map<Integer, int[]> table = solveNet(corpus);

		Set<Integer> allCmds = new java.util.TreeSet<>(table.keySet());
		System.out.println("\nopcode  netInt  netStr   (? = unresolved)");
		for (int op : allCmds)
		{
			int[] v = table.get(op);
			System.out.println(String.format("%6d  %6s  %6s", op,
				v[0] == UNRESOLVED ? "?" : v[0], v[1] == UNRESOLVED ? "?" : v[1]));
		}
		System.exit(0);
	}

	/** Sentinel net-effect value meaning "could not be solved". */
	public static final int UNRESOLVED = Integer.MIN_VALUE;

	/** All scripts reachable from interface onLoad listeners (plus their gosub/callback targets). */
	public static List<ScriptDefinition> buildCorpus(MapEditorService service) throws Exception
	{
		InterfaceDefinition[][] interfaces = service.getInterfaces();
		Set<Integer> seen = new HashSet<>();
		Deque<Integer> queue = new ArrayDeque<>();
		for (InterfaceDefinition[] group : interfaces)
		{
			if (group == null) continue;
			for (InterfaceDefinition d : group)
			{
				if (d == null || d.onLoadListener == null || d.onLoadListener.length == 0) continue;
				if (d.onLoadListener[0] instanceof Integer) queue.add((Integer) d.onLoadListener[0]);
			}
		}
		List<ScriptDefinition> corpus = new ArrayList<>();
		while (!queue.isEmpty())
		{
			int id = queue.poll();
			if (!seen.add(id)) continue;
			ScriptDefinition s = service.getScript(id);
			if (s == null) continue;
			corpus.add(s);
			int[] ins = s.getInstructions(), iop = s.getIntOperands();
			for (int i = 0; i < ins.length; i++)
			{
				if (ins[i] == 40) queue.add(iop[i]);            // gosub target
				else if (ins[i] == 0 && iop[i] > 0 && iop[i] < 40000) queue.add(iop[i]); // maybe a callback id
			}
		}
		return corpus;
	}

	/**
	 * Solve each command opcode's net stack effect (POPS − pushes) for ints and strings, from the
	 * branch-free scripts in the corpus. Returns opcode -> {netInt, netStr}; either may be
	 * {@link #UNRESOLVED}. A positive value = ints/strings consumed; negative = produced (a query).
	 */
	public static Map<Integer, int[]> solveNet(List<ScriptDefinition> corpus)
	{
		List<Map<Integer, Integer>> eqIntTerms = new ArrayList<>();
		List<Integer> eqIntConst = new ArrayList<>();
		List<Map<Integer, Integer>> eqStrTerms = new ArrayList<>();
		List<Integer> eqStrConst = new ArrayList<>();

		for (ScriptDefinition s : corpus)
		{
			int[] ins = s.getInstructions(), iop = s.getIntOperands();
			boolean branchFree = true;
			for (int op : ins) if (isBranch(op) || op == 40) { branchFree = false; break; }
			if (!branchFree) continue;
			int intPush = 0, strPush = 0;
			Map<Integer, Integer> cmdInt = new HashMap<>(), cmdStr = new HashMap<>();
			for (int i = 0; i < ins.length; i++)
			{
				int op = ins[i];
				if (op == 21) continue;
				int[] cn = coreNet(op, iop[i]);
				if (cn != null)
				{
					intPush += cn[0];
					strPush += cn[1];
				}
				else
				{
					cmdInt.merge(op, 1, Integer::sum);
					cmdStr.merge(op, 1, Integer::sum);
				}
			}
			eqIntTerms.add(cmdInt);
			eqIntConst.add(intPush);
			eqStrTerms.add(cmdStr);
			eqStrConst.add(strPush);
		}

		Map<Integer, Integer> netInt = new HashMap<>(), netStr = new HashMap<>();
		solve(eqIntTerms, eqIntConst, netInt);
		solve(eqStrTerms, eqStrConst, netStr);

		Set<Integer> allCmds = new java.util.TreeSet<>();
		for (Map<Integer, Integer> m : eqIntTerms) allCmds.addAll(m.keySet());
		Map<Integer, int[]> table = new HashMap<>();
		for (int op : allCmds)
		{
			table.put(op, new int[]{
				netInt.containsKey(op) ? netInt.get(op) : UNRESOLVED,
				netStr.containsKey(op) ? netStr.get(op) : UNRESOLVED});
		}
		return table;
	}

	/**
	 * Iteratively resolve, ROBUST to a minority of mis-analysed scripts: each pass, for every unknown
	 * command, gather the implied value from every equation where it is the lone remaining unknown, and
	 * accept it only if a strong majority of those equations agree (outlier equations are outvoted).
	 */
	static void solve(List<Map<Integer, Integer>> terms, List<Integer> consts, Map<Integer, Integer> known)
	{
		boolean progress = true;
		while (progress)
		{
			progress = false;
			Map<Integer, Map<Integer, Integer>> votes = new HashMap<>(); // op -> (value -> count)
			for (int e = 0; e < terms.size(); e++)
			{
				int rhs = consts.get(e);
				int unknownOp = Integer.MIN_VALUE, unknownCount = 0, unknownVars = 0;
				for (Map.Entry<Integer, Integer> t : terms.get(e).entrySet())
				{
					if (known.containsKey(t.getKey())) rhs -= known.get(t.getKey()) * t.getValue();
					else { unknownVars++; unknownOp = t.getKey(); unknownCount = t.getValue(); }
				}
				if (unknownVars == 1 && unknownCount != 0 && rhs % unknownCount == 0)
				{
					votes.computeIfAbsent(unknownOp, k -> new HashMap<>()).merge(rhs / unknownCount, 1, Integer::sum);
				}
			}
			progress |= tally(votes, known);

			// 2-unknown resolution: group reduced equations by their pair of unknowns; each distinct
			// pair of equations sharing a pair yields a 2x2 system → candidate values; vote.
			Map<String, List<int[]>> pairEqs = new HashMap<>(); // "a,b" -> list of {ca, cb, rhs}
			Map<String, int[]> pairOps = new HashMap<>();
			for (int e = 0; e < terms.size(); e++)
			{
				int rhs = consts.get(e);
				List<int[]> unk = new ArrayList<>(); // {op, coeff}
				for (Map.Entry<Integer, Integer> t : terms.get(e).entrySet())
				{
					if (known.containsKey(t.getKey())) rhs -= known.get(t.getKey()) * t.getValue();
					else unk.add(new int[]{t.getKey(), t.getValue()});
				}
				if (unk.size() == 2)
				{
					unk.sort((x, y) -> Integer.compare(x[0], y[0]));
					String key = unk.get(0)[0] + "," + unk.get(1)[0];
					pairEqs.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{unk.get(0)[1], unk.get(1)[1], rhs});
					pairOps.putIfAbsent(key, new int[]{unk.get(0)[0], unk.get(1)[0]});
				}
			}
			Map<Integer, Map<Integer, Integer>> votes2 = new HashMap<>();
			for (Map.Entry<String, List<int[]>> pe : pairEqs.entrySet())
			{
				List<int[]> eqs = pe.getValue();
				int[] ops = pairOps.get(pe.getKey());
				for (int i = 0; i < eqs.size(); i++)
				{
					for (int j = i + 1; j < eqs.size(); j++)
					{
						int[] e1 = eqs.get(i), e2 = eqs.get(j);
						long det = (long) e1[0] * e2[1] - (long) e2[0] * e1[1];
						if (det == 0) continue;
						long na = (long) e1[2] * e2[1] - (long) e2[2] * e1[1];
						long nb = (long) e1[0] * e2[2] - (long) e2[0] * e1[2];
						if (na % det != 0 || nb % det != 0) continue;
						int va = (int) (na / det), vb = (int) (nb / det);
						votes2.computeIfAbsent(ops[0], k -> new HashMap<>()).merge(va, 1, Integer::sum);
						votes2.computeIfAbsent(ops[1], k -> new HashMap<>()).merge(vb, 1, Integer::sum);
					}
				}
			}
			progress |= tally(votes2, known);
		}
	}

	/** Accept a voted value for each op when its plurality is decisive (single sample or >=2/3). */
	static boolean tally(Map<Integer, Map<Integer, Integer>> votes, Map<Integer, Integer> known)
	{
		boolean progress = false;
		for (Map.Entry<Integer, Map<Integer, Integer>> ve : votes.entrySet())
		{
			if (known.containsKey(ve.getKey())) continue;
			int bestVal = 0, bestCount = 0, total = 0;
			for (Map.Entry<Integer, Integer> v : ve.getValue().entrySet())
			{
				total += v.getValue();
				if (v.getValue() > bestCount) { bestCount = v.getValue(); bestVal = v.getKey(); }
			}
			if (bestCount == total || bestCount * 3 >= total * 2)
			{
				known.put(ve.getKey(), bestVal);
				progress = true;
			}
		}
		return progress;
	}
}
