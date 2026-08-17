/*
 * CS2 (ClientScript2) bytecode loader. Decodes a script archive from cache index 12 into a
 * ScriptDefinition. Format matches the OSRS client / RuneLite ScriptLoader (BSD): the trailer at the
 * end carries the opcode count, local/argument counts and the switch tables; instructions run from
 * offset 0 up to the trailer.
 */
package net.runelite.cache.definitions.loaders;

import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.definitions.ScriptDefinition;
import net.runelite.cache.io.InputStream;

public class ScriptLoader
{
	// Opcodes whose operand is a 4-byte int are the norm below 100; these three are the exceptions
	// that take a 1-byte operand, alongside everything >= 100.
	private static final int SCONST = 3;
	private static final int RETURN = 21;
	private static final int POP_INT = 38;
	private static final int POP_STRING = 39;

	public ScriptDefinition load(int id, byte[] b)
	{
		ScriptDefinition def = new ScriptDefinition();
		def.setId(id);
		InputStream in = new InputStream(b);

		in.setOffset(in.getLength() - 2);
		int switchLength = in.readUnsignedShort();
		int instructionsEnd = in.getLength() - switchLength - 2 - 12;

		in.setOffset(instructionsEnd);
		int numOpcodes = in.readInt();
		def.setLocalIntCount(in.readUnsignedShort());
		def.setLocalObjCount(in.readUnsignedShort());
		def.setIntArgCount(in.readUnsignedShort());
		def.setObjArgCount(in.readUnsignedShort());

		int numSwitches = in.readUnsignedByte();
		if (numSwitches > 0)
		{
			@SuppressWarnings("unchecked")
			Map<Integer, Integer>[] switches = new Map[numSwitches];
			def.setSwitches(switches);
			for (int i = 0; i < numSwitches; ++i)
			{
				int count = in.readUnsignedShort();
				Map<Integer, Integer> map = switches[i] = new HashMap<>();
				while (count-- > 0)
				{
					int key = in.readInt();
					int offset = in.readInt();
					map.put(key, offset);
				}
			}
		}

		// This cache revision prefixes the instruction stream with a 1-byte header (a leading flag,
		// zero here); the opcodes begin at offset 1. Reading from 0 shifts everything and turns string
		// constants into garbage opcodes.
		in.setOffset(1);
		java.util.List<Integer> ins = new java.util.ArrayList<>(numOpcodes);
		java.util.List<Integer> iop = new java.util.ArrayList<>(numOpcodes);
		java.util.List<String> sop = new java.util.ArrayList<>(numOpcodes);

		while (in.getOffset() < instructionsEnd)
		{
			int opcode = in.readUnsignedShort();
			String s = null;
			int io = 0;
			if (opcode == SCONST)
			{
				s = in.readString();
			}
			else if (opcode < 100 && opcode != RETURN && opcode != POP_INT && opcode != POP_STRING)
			{
				io = in.readInt();
			}
			else
			{
				io = in.readUnsignedByte();
			}
			ins.add(opcode);
			iop.add(io);
			sop.add(s);
		}

		int n = ins.size();
		int[] instructions = new int[n];
		int[] intOperands = new int[n];
		String[] stringOperands = new String[n];
		for (int k = 0; k < n; k++)
		{
			instructions[k] = ins.get(k);
			intOperands[k] = iop.get(k);
			stringOperands[k] = sop.get(k);
		}
		if (n != numOpcodes)
		{
			System.err.println("ScriptLoader " + id + ": parsed " + n + " opcodes, header says " + numOpcodes);
		}

		def.setInstructions(instructions);
		def.setIntOperands(intOperands);
		def.setStringOperands(stringOperands);
		return def;
	}
}
