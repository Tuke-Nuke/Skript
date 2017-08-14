/*
 *   This file is part of Skript.
 *
 *  Skript is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Skript is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Skript.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * 
 * Copyright 2011-2016 Peter Güttinger and contributors
 * 
 */

package ch.njol.skript.lang;

import org.bukkit.event.Event;
import org.eclipse.jdt.annotation.Nullable;

import ch.njol.skript.Skript;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;

/**
 *
 */
public class ScopeSection extends TriggerSection{
		
	public ScopeSection(SectionNode node){
		super(node);
	}
	
	@Override
	public String toString(@Nullable Event e, boolean debug) {
		// TODO Auto-generated method stub
		return "walk";
	}

	@Override
	@Nullable
	public TriggerItem walk(Event e) {
		return walk(e, true);
	}
	
	public boolean execute(Event e){
		TriggerItem next = walk(e);
		if (next != null)
			TriggerItem.walk(next, e);
		return next != null;
	}
	
}
