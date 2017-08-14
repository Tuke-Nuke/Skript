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

import java.util.Iterator;

import org.bukkit.event.Event;
import org.eclipse.jdt.annotation.Nullable;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;

/**
 *
 */
public abstract class Scope extends Effect{
	
	@Nullable
	private static ScopeSection lastSection = null;
	
	@Nullable
	private ScopeSection section = lastSection;
	
	protected Scope() {
		section = lastSection;
	}
	
	public boolean hasSection() {
		return section != null;
	}
		
	@Nullable
	public ScopeSection getSection(){
		return section;
	}
	
	@SuppressWarnings({"null", "unchecked", "rawtypes"})
	public static @Nullable Scope parse(String s, @Nullable final String defaultError) {
		return (Scope) SkriptParser.parse(s, (Iterator) Skript.getScopes().iterator(), defaultError);
	}
	
	public static void setLastSection(@Nullable ScopeSection section){
		lastSection = section;
	}
}
