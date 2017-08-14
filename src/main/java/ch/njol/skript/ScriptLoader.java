/**
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
 * Copyright 2011-2017 Peter Güttinger and contributors
 */
package ch.njol.skript;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;

import ch.njol.skript.lang.*;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.eclipse.jdt.annotation.Nullable;

import ch.njol.skript.aliases.Aliases;
import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.command.CommandEvent;
import ch.njol.skript.command.Commands;
import ch.njol.skript.command.ScriptCommand;
import ch.njol.skript.config.Config;
import ch.njol.skript.config.EntryNode;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.config.SimpleNode;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.function.Function;
import ch.njol.skript.lang.function.FunctionEvent;
import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.lang.function.Signature;
import ch.njol.skript.localization.Language;
import ch.njol.skript.localization.Message;
import ch.njol.skript.localization.PluralizingArgsMessage;
import ch.njol.skript.log.CountingLogHandler;
import ch.njol.skript.log.ErrorDescLogHandler;
import ch.njol.skript.log.ParseLogHandler;
import ch.njol.skript.log.RetainingLogHandler;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.registrations.Converters;
import ch.njol.skript.util.Date;
import ch.njol.skript.util.ExceptionUtils;
import ch.njol.skript.util.Task;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Callback;
import ch.njol.util.Kleenean;
import ch.njol.util.NonNullPair;
import ch.njol.util.StringUtils;
import ch.njol.util.coll.CollectionUtils;

/**
 * @author Peter Güttinger
 */
final public class ScriptLoader {
	private ScriptLoader() {}
	
	private final static Message m_no_errors = new Message("skript.no errors"),
			m_no_scripts = new Message("skript.no scripts");
	private final static PluralizingArgsMessage m_scripts_loaded = new PluralizingArgsMessage("skript.scripts loaded");
	
	@Nullable
	public static Config currentScript = null;
	
	/**
	 * use {@link #setCurrentEvent(String, Class...)}
	 */
	@Nullable
	private static String currentEventName = null;
	
	@Nullable
	public static String getCurrentEventName() {
		return currentEventName;
	}
	
	/**
	 * use {@link #setCurrentEvent(String, Class...)}
	 */
	@Nullable
	private static Class<? extends Event>[] currentEvents = null;
	
	/**
	 * Call {@link #deleteCurrentEvent()} after parsing
	 * 
	 * @param name
	 * @param events
	 */
	public static void setCurrentEvent(final String name, final @Nullable Class<? extends Event>... events) {
		currentEventName = name;
		currentEvents = events;
		hasDelayBefore = Kleenean.FALSE;
	}
	
	public static void deleteCurrentEvent() {
		currentEventName = null;
		currentEvents = null;
		hasDelayBefore = Kleenean.FALSE;
	}
	
	public static List<TriggerSection> currentSections = new ArrayList<>();
	public static List<Loop> currentLoops = new ArrayList<>();
	private final static Map<String, ItemType> currentAliases = new HashMap<>();
	final static HashMap<String, String> currentOptions = new HashMap<>();
	
	public static Map<String, ItemType> getScriptAliases() {
		return currentAliases;
	}
	
	/**
	 * must be synchronized
	 */
	private final static ScriptInfo loadedScripts = new ScriptInfo();
	
	public static Kleenean hasDelayBefore = Kleenean.FALSE;
	
	public static class ScriptInfo {
		public int files, triggers, commands, functions;
		
		public ScriptInfo() {}
		
		public ScriptInfo(final int numFiles, final int numTriggers, final int numCommands, final int numFunctions) {
			files = numFiles;
			triggers = numTriggers;
			commands = numCommands;
			functions = numFunctions;
		}
		
		/**
		 * Copy constructor.
		 * @param loadedscripts
		 */
		public ScriptInfo(ScriptInfo o) {
			files = o.files;
			triggers = o.triggers;
			commands = o.commands;
			functions = o.functions;
		}

		public void add(final ScriptInfo other) {
			files += other.files;
			triggers += other.triggers;
			commands += other.commands;
			functions += other.functions;
		}
		
		public void subtract(final ScriptInfo other) {
			files -= other.files;
			triggers -= other.triggers;
			commands -= other.commands;
			functions -= other.functions;
		}
		
		@Override
		public String toString() {
			return "ScriptInfo{files=" + files + ",triggers=" + triggers + ",commands=" + commands + ",functions:" + functions + "}";
		}
	}
	
//	private final static class SerializedScript {
//		public SerializedScript() {}
//
//		public final List<Trigger> triggers = new ArrayList<Trigger>();
//		public final List<ScriptCommand> commands = new ArrayList<ScriptCommand>();
//	}
	
	private static String indentation = "";
	
	// Load scripts in separate (one) thread
	static final BlockingQueue<Runnable> loadQueue = new ArrayBlockingQueue<>(20, true);
	static final Thread loaderThread;
	static boolean loadAsync; // See below
	
	/**
	 * Checks if scripts are loaded in separate thread. If true,
	 * following behavior should be expected:
	 * <ul>
	 * <li>Scripts are still unloaded and enabled in server thread
	 * <li>When reloading a script, old version is unloaded <i>after</i> it has
	 * been parsed, immediately before it has been loaded
	 * <li>When reloading all scripts, scripts that were removed are disabled
	 * after everything has been reloaded
	 * <li>Script infos returned by most methods are inaccurate
	 * @return If main thread is not blocked when loading.
	 */
	public static boolean isAsync() {
		return loadAsync;
	}
	
	/**
	 * All loaded script files.
	 */
	@SuppressWarnings("null")
	static final Set<File> loadedFiles = Collections.synchronizedSet(new HashSet<>());
	
	// Initialize and start load thread
	static {
		loaderThread = new AsyncLoaderThread();
		loaderThread.start();
	}
	
	private static class AsyncLoaderThread extends Thread {
		
		public AsyncLoaderThread() { }

		@Override
		public void run() {
			while (true) {
				try {
					loadQueue.take().run();
				} catch (InterruptedException e) {
					Skript.exception(e); // Bubble it up with instructions on how to report it
				}
			}
		}
	}
	
	static void loadScripts() {
		final File scriptsFolder = new File(Skript.getInstance().getDataFolder(), Skript.SCRIPTSFOLDER + File.separator);
		if (!scriptsFolder.isDirectory())
			scriptsFolder.mkdirs();
		
		final Date start = new Date();
		
		Runnable task = () -> {
			final Set<File> oldLoadedFiles = new HashSet<>(loadedFiles);
			
			final ScriptInfo i;
			
			final ErrorDescLogHandler h = SkriptLogger.startLogHandler(new ErrorDescLogHandler(null, null, m_no_errors.toString()));
			try {
				Language.setUseLocal(false);
				
				List<Config> configs = loadStructures(scriptsFolder);
				i = loadScripts(configs);
			} finally {
				Language.setUseLocal(true);
				h.stop();
			}
			
			// Now, make sure that old files that are no longer there are unloaded
			// Only if this is done using async loading, though!
			if (loadAsync) {
				oldLoadedFiles.removeAll(loadedFiles);
				for (File script : oldLoadedFiles) {
					assert script != null;
					
					// Use internal unload method which does not call validateFunctions()
					unloadScript_(script);
					Functions.clearFunctions(script);
				}
				Functions.validateFunctions(); // Manually validate functions
			}
			
			if (i.files == 0)
				Skript.warning(m_no_scripts.toString());
			if (Skript.logNormal() && i.files > 0)
				Skript.info(m_scripts_loaded.toString(i.files, i.triggers, i.commands, start.difference(new Date())));
			
			SkriptEventHandler.registerBukkitEvents();
		};
		if (loadAsync)
			loadQueue.add(task);
		else
			task.run();
	}
	
	/**
	 * Filter for enabled scripts & folders.
	 */
	private final static FileFilter scriptFilter = new FileFilter() {
		@Override
		public boolean accept(final @Nullable File f) {
			return f != null && (f.isDirectory() || StringUtils.endsWithIgnoreCase("" + f.getName(), ".sk")) && !f.getName().startsWith("-");
		}
	};
	
	/**
	 * Loads the specified scripts.
	 * 
	 * @param configs Configs for scripts, loaded by {@link #loadStructures(File[])}
	 * @return Info on the loaded scripts.
	 */
	public final static ScriptInfo loadScripts(final List<Config> configs) {
		ScriptInfo i = new ScriptInfo();
		
		Runnable task = () -> {
			// Do NOT sort here, list must be loaded in order it came in (see issue #667)
			final boolean wasLocal = Language.setUseLocal(false);
			try {
				for (final Config cfg : configs) {
					assert cfg != null : configs.toString();
					i.add(loadScript(cfg));
				}
			} finally {
				if (wasLocal)
					Language.setUseLocal(true);
			}
			
			SkriptEventHandler.registerBukkitEvents();
		};
		if (loadAsync && Bukkit.isPrimaryThread())
			loadQueue.add(task);
		else
			task.run();
		
		// If task was ran asynchronously, returned stats may be wrong
		// This is probably ok, since loadScripts() will go async if needed
		return i;
	}
	
	/**
	 * Loads the specified scripts.
	 * 
	 * @param configs Configs for scripts, loaded by {@link #loadStructure(File)}
	 * @return Info on the loaded scripts
	 */
	@SuppressWarnings("null")
	public final static ScriptInfo loadScripts(final Config... configs) {
		return loadScripts(Arrays.asList(configs));
	}
	
	/**
	 * Represents data for event which is waiting to be loaded.
	 */
	private static class ParsedEventData {
		
		public ParsedEventData(NonNullPair<SkriptEventInfo<?>, SkriptEvent> info, String event, SectionNode node, List<TriggerItem> items) {
			this.info = info;
			this.event = event;
			this.node = node;
			this.items = items;
		}
		
		public final NonNullPair<SkriptEventInfo<?>, SkriptEvent> info;
		public final String event;
		public final SectionNode node;
		public final List<TriggerItem> items;
	}
	
	/**
	 * Loads one script. Only for internal use, as this doesn't register/update
	 * event handlers.
	 * @param config Config for script to be loaded.
	 * @return Info about script that is loaded
	 */
	@SuppressWarnings("unchecked")
	private final static ScriptInfo loadScript(final @Nullable Config config) {
		if (config == null) { // Something bad happened, hopefully got logged to console
			return new ScriptInfo();
		}
		
		// When something is parsed, it goes there to be loaded later
		List<ScriptCommand> commands = new ArrayList<>();
		List<Function<?>> functions = new ArrayList<>();
		List<ParsedEventData> events = new ArrayList<>();
		
		// Track what is loaded
		int numTriggers = 0;
		int numCommands = 0;
		int numFunctions = 0;
		
		try {
			if (SkriptConfig.keepConfigsLoaded.value())
				SkriptConfig.configs.add(config);
			
			currentAliases.clear();
			currentOptions.clear();
			currentScript = config;
			
//			final SerializedScript script = new SerializedScript();
			
			final CountingLogHandler numErrors = SkriptLogger.startLogHandler(new CountingLogHandler(SkriptLogger.SEVERE));
			
			try {
				for (final Node cnode : config.getMainNode()) {
					if (!(cnode instanceof SectionNode)) {
						Skript.error("invalid line - all code has to be put into triggers");
						continue;
					}
					
					final SectionNode node = ((SectionNode) cnode);
					String event = node.getKey();
					if (event == null)
						continue;
					
					if (event.equalsIgnoreCase("aliases")) {
						node.convertToEntries(0, "=");
						for (final Node n : node) {
							if (!(n instanceof EntryNode)) {
								Skript.error("invalid line in aliases section");
								continue;
							}
							final ItemType t = Aliases.parseAlias(((EntryNode) n).getValue());
							if (t == null)
								continue;
							currentAliases.put(((EntryNode) n).getKey().toLowerCase(), t);
						}
						continue;
					} else if (event.equalsIgnoreCase("options")) {
						node.convertToEntries(0);
						for (final Node n : node) {
							if (!(n instanceof EntryNode)) {
								Skript.error("invalid line in options");
								continue;
							}
							currentOptions.put(((EntryNode) n).getKey(), ((EntryNode) n).getValue());
						}
						continue;
					} else if (event.equalsIgnoreCase("variables")) {
						// TODO allow to make these override existing variables
						node.convertToEntries(0, "=");
						for (final Node n : node) {
							if (!(n instanceof EntryNode)) {
								Skript.error("Invalid line in variables section");
								continue;
							}
							String name = ((EntryNode) n).getKey().toLowerCase(Locale.ENGLISH);
							if (name.startsWith("{") && name.endsWith("}"))
								name = "" + name.substring(1, name.length() - 1);
							final String var = name;
							name = StringUtils.replaceAll(name, "%(.+)?%", new Callback<String, Matcher>() {
								@Override
								@Nullable
								public String run(final Matcher m) {
									if (m.group(1).contains("{") || m.group(1).contains("}") || m.group(1).contains("%")) {
										Skript.error("'" + var + "' is not a valid name for a default variable");
										return null;
									}
									final ClassInfo<?> ci = Classes.getClassInfoFromUserInput("" + m.group(1));
									if (ci == null) {
										Skript.error("Can't understand the type '" + m.group(1) + "'");
										return null;
									}
									return "<" + ci.getCodeName() + ">";
								}
							});
							if (name == null) {
								continue;
							} else if (name.contains("%")) {
								Skript.error("Invalid use of percent signs in variable name");
								continue;
							}
							if (Variables.getVariable(name, null, false) != null)
								continue;
							Object o;
							final ParseLogHandler log = SkriptLogger.startParseLogHandler();
							try {
								o = Classes.parseSimple(((EntryNode) n).getValue(), Object.class, ParseContext.SCRIPT);
								if (o == null) {
									log.printError("Can't understand the value '" + ((EntryNode) n).getValue() + "'");
									continue;
								}
								log.printLog();
							} finally {
								log.stop();
							}
							final ClassInfo<?> ci = Classes.getSuperClassInfo(o.getClass());
							if (ci.getSerializer() == null) {
								Skript.error("Can't save '" + ((EntryNode) n).getValue() + "' in a variable");
								continue;
							} else if (ci.getSerializeAs() != null) {
								final ClassInfo<?> as = Classes.getExactClassInfo(ci.getSerializeAs());
								if (as == null) {
									assert false : ci;
									continue;
								}
								o = Converters.convert(o, as.getC());
								if (o == null) {
									Skript.error("Can't save '" + ((EntryNode) n).getValue() + "' in a variable");
									continue;
								}
							}
							Variables.setVariable(name, o, null, false);
						}
						continue;
					}
					
					if (!SkriptParser.validateLine(event))
						continue;
					
					if (event.toLowerCase().startsWith("command ")) {
						
						setCurrentEvent("command", CommandEvent.class);
						
						final ScriptCommand c = Commands.loadCommand(node, false);
						if (c != null) {
							commands.add(c);
						}
						numCommands++;
						
						deleteCurrentEvent();
						
						continue;
					} else if (event.toLowerCase().startsWith("function ")) {
						
						setCurrentEvent("function", FunctionEvent.class);
						
						final Function<?> func = Functions.loadFunction(node);
						if (func != null) {
							functions.add(func);
						}
						numFunctions++;
						
						deleteCurrentEvent();
						
						continue;
					}
					
					if (Skript.logVeryHigh() && !Skript.debug())
						Skript.info("loading trigger '" + event + "'");
					
					if (StringUtils.startsWithIgnoreCase(event, "on "))
						event = "" + event.substring("on ".length());
					
					event = replaceOptions(event);
					
					final NonNullPair<SkriptEventInfo<?>, SkriptEvent> parsedEvent = SkriptParser.parseEvent(event, "can't understand this event: '" + node.getKey() + "'");
					if (parsedEvent == null)
						continue;
					
					if (Skript.debug() || node.debug())
						Skript.debug(event + " (" + parsedEvent.getSecond().toString(null, true) + "):");
					
					try {
						setCurrentEvent("" + parsedEvent.getFirst().getName().toLowerCase(Locale.ENGLISH), parsedEvent.getFirst().events);
						events.add(new ParsedEventData(parsedEvent, event, node, loadItems(node)));
					} finally {
						deleteCurrentEvent();
					}
					
					if (parsedEvent.getSecond() instanceof SelfRegisteringSkriptEvent) {
						((SelfRegisteringSkriptEvent) parsedEvent.getSecond()).afterParse(config);
					}
					
					numTriggers++;
				}
				
				if (Skript.logHigh())
					Skript.info("loaded " + numTriggers + " trigger" + (numTriggers == 1 ? "" : "s") + " and " + numCommands + " command" + (numCommands == 1 ? "" : "s") + " from '" + config.getFileName() + "'");
				
				currentScript = null;
			} finally {
				numErrors.stop();
			}
		} catch (final Exception e) {
			Skript.exception(e, "Could not load " + config.getFileName());
		} finally {
			SkriptLogger.setNode(null);
		}
		
		// In always sync task, enable stuff
		Callable<Void> callable = new Callable<Void>() {

			@SuppressWarnings("synthetic-access")
			@Override
			public @Nullable Void call() throws Exception {				
				// Unload script IF we're doing async stuff
				// (else it happened already)
				File file = config.getFile();
				if (loadAsync) {
					if (file != null)
						unloadScript_(file);
				}
				
				// Now, enable everything!
				for (ScriptCommand command : commands) {
					assert command != null;
					Commands.registerCommand(command);
				}
				
				for (Function<?> func : functions) {
					assert func != null;
					Functions.putFunction(func);
				}
				
				for (ParsedEventData event : events) {
					setCurrentEvent("" + event.info.getFirst().getName().toLowerCase(Locale.ENGLISH), event.info.getFirst().events);
					
					final Trigger trigger;
					try {
						trigger = new Trigger(config.getFile(), event.event, event.info.getSecond(), event.items);
						trigger.setLineNumber(event.node.getLine()); // Set line number for debugging
						trigger.setDebugLabel(config.getFileName() + ": line " + event.node.getLine());
					} finally {
						deleteCurrentEvent();
					}
					
					if (event.info.getSecond() instanceof SelfRegisteringSkriptEvent) {
						((SelfRegisteringSkriptEvent) event.info.getSecond()).register(trigger);
						SkriptEventHandler.addSelfRegisteringTrigger(trigger);
					} else {
						SkriptEventHandler.addTrigger(event.info.getFirst().events, trigger);
					}
					
					deleteCurrentEvent();
				}
				
				// Add to loaded files to use for future reloads
				loadedFiles.add(file);
				
				return null;
			}
		};
		if (loadAsync) { // Need to delegate to main thread
			Task.callSync(callable);
		} else { // We are in main thread, execute immediately
			try {
				callable.call();
			} catch (Exception e) {
				Skript.exception(e);
			}
		}
		
		return new ScriptInfo(1, numTriggers, numCommands, numFunctions);
	}
	
	/**
	 * Loads structures of specified scripts.
	 * 
	 * @param files
	 */
	public final static List<Config> loadStructures(final File[] files) {
		Arrays.sort(files);
		
		List<Config> loadedFiles = new ArrayList<>(files.length);
		for (final File f : files) {
			assert f != null : Arrays.toString(files);
			loadedFiles.add(loadStructure(f));
		}
		
		return loadedFiles;
	}
	
	/**
	 * Loads structures of all scripts in given directory.
	 * 
	 * @param directory
	 */
	public final static List<Config> loadStructures(final File directory) {
		final File[] files = directory.listFiles(scriptFilter);
		Arrays.sort(files);
		
		List<Config> loadedFiles = new ArrayList<>(files.length);
		for (final File f : files) {
			if (f.isDirectory()) {
				loadedFiles.addAll(loadStructures(f));
			} else {
				loadedFiles.add(loadStructure(f));
			}
		}
		return loadedFiles;
	}
	
	/**
	 * Loads structure of given script, currently only for functions. Must be called before
	 * actually loading that script.
	 * @param f Script
	 */
	@SuppressWarnings("unchecked")
	public final static @Nullable Config loadStructure(final File f) {
		if (!f.exists()) { // If file does not exist...
			unloadScript(f); // ... it might be good idea to unload it now
			return null;
		}
		
		try {
			final Config config = new Config(f, true, false, ":");
			
			//final CountingLogHandler numErrors = SkriptLogger.startLogHandler(new CountingLogHandler(SkriptLogger.SEVERE));
			
			try {
				for (final Node cnode : config.getMainNode()) {
					if (!(cnode instanceof SectionNode)) {
						// Don't spit error yet, we are only pre-parsing...
						continue;
					}
					
					final SectionNode node = ((SectionNode) cnode);
					String event = node.getKey();
					if (event == null)
						continue;
					
					
					if (!SkriptParser.validateLine(event))
						continue;
					
					if (event.toLowerCase().startsWith("function ")) {
						
						setCurrentEvent("function", FunctionEvent.class);
						
						final Signature<?> func = Functions.loadSignature(config.getFileName(), node);
						
						deleteCurrentEvent();
						
						continue;
					}
				}
				
				currentScript = null;
			} finally {
				//numErrors.stop();
			}
			SkriptLogger.setNode(null);
			return config;
		} catch (final IOException e) {
			Skript.error("Could not load " + f.getName() + ": " + ExceptionUtils.toString(e));
		} catch (final Exception e) {
			Skript.exception(e, "Could not load " + f.getName());
		} finally {
			SkriptLogger.setNode(null);
		}
		return null; // Oops something went wrong
	}
	
	/**
	 * Unloads enabled scripts from the specified directory and its subdirectories.
	 * 
	 * @param folder
	 * @return Info on the unloaded scripts
	 */
	final static ScriptInfo unloadScripts(final File folder) {
		final ScriptInfo r = unloadScripts_(folder);
		//Functions.validateFunctions();
		return r;
	}
	
	private final static ScriptInfo unloadScripts_(final File folder) {
		final ScriptInfo info = new ScriptInfo();
		final File[] files = folder.listFiles(scriptFilter);
		for (final File f : files) {
			if (f.isDirectory()) {
				info.add(unloadScripts_(f));
			} else if (f.getName().endsWith(".sk")) {
				info.add(unloadScript_(f));
			}
		}
		return info;
	}
	
	/**
	 * Unloads the specified script.
	 * 
	 * @param script
	 * @return Info on the unloaded script
	 */
	final static ScriptInfo unloadScript(final File script) {
		final ScriptInfo r = unloadScript_(script);
		Functions.clearFunctions(script);
		Functions.validateFunctions();
		return r;
	}
	
	private final static ScriptInfo unloadScript_(final File script) {
		if (loadedFiles.contains(script)) {
			final ScriptInfo info = SkriptEventHandler.removeTriggers(script); // Remove triggers
			synchronized (loadedScripts) { // Update script info
				loadedScripts.subtract(info);
			}
			
			loadedFiles.remove(script); // We just unloaded it, so...
			return info; // Return how much we unloaded
		}
		
		return new ScriptInfo(); // Return that we unloaded literally nothing
	}
	
	public final static String replaceOptions(final String s) {
		final String r = StringUtils.replaceAll(s, "\\{@(.+?)\\}", new Callback<String, Matcher>() {
			@Override
			@Nullable
			public String run(final Matcher m) {
				final String option = currentOptions.get(m.group(1));
				if (option == null) {
					Skript.error("undefined option " + m.group());
					return m.group();
				}
				return option;
			}
		});
		assert r != null;
		return r;
	}
	
	@SuppressWarnings("unchecked")
	public static ArrayList<TriggerItem> loadItems(final SectionNode node) {
		
		if (Skript.debug())
			indentation += "    ";
		
		final ArrayList<TriggerItem> items = new ArrayList<>();
		
		Kleenean hadDelayBeforeLastIf = Kleenean.FALSE;
		
		for (final Node n : node) {
			SkriptLogger.setNode(n);
			if (n instanceof SimpleNode) {
				final SimpleNode e = (SimpleNode) n;
				final String s = replaceOptions("" + e.getKey());
				if (!SkriptParser.validateLine(s))
					continue;
				final Statement stmt = Statement.parse(s, "Can't understand this condition/effect: " + s);
				if (stmt == null)
					continue;
				if (Skript.debug() || n.debug())
					Skript.debug(indentation + stmt.toString(null, true));
				items.add(stmt);
				if (stmt instanceof Delay)
					hasDelayBefore = Kleenean.TRUE;
			} else if (n instanceof SectionNode) {
				String name = replaceOptions("" + n.getKey());
				if (!SkriptParser.validateLine(name))
					continue;
				
				if (StringUtils.startsWithIgnoreCase(name, "loop ")) {
					final String l = "" + name.substring("loop ".length());
					final RetainingLogHandler h = SkriptLogger.startRetainingLog();
					Expression<?> loopedExpr;
					try {
						loopedExpr = new SkriptParser(l).parseExpression(Object.class);
						if (loopedExpr != null)
							loopedExpr = loopedExpr.getConvertedExpression(Object.class);
						if (loopedExpr == null) {
							h.printErrors("Can't understand this loop: '" + name + "'");
							continue;
						}
						h.printLog();
					} finally {
						h.stop();
					}
					if (loopedExpr.isSingle()) {
						Skript.error("Can't loop " + loopedExpr + " because it's only a single value");
						continue;
					}
					if (Skript.debug() || n.debug())
						Skript.debug(indentation + "loop " + loopedExpr.toString(null, true) + ":");
					final Kleenean hadDelayBefore = hasDelayBefore;
					items.add(new Loop(loopedExpr, (SectionNode) n));
					if (hadDelayBefore != Kleenean.TRUE && hasDelayBefore != Kleenean.FALSE)
						hasDelayBefore = Kleenean.UNKNOWN;
				} else if (StringUtils.startsWithIgnoreCase(name, "while ")) {
					final String l = "" + name.substring("while ".length());
					final Condition c = Condition.parse(l, "Can't understand this condition: " + l);
					if (c == null)
						continue;
					if (Skript.debug() || n.debug())
						Skript.debug(indentation + "while " + c.toString(null, true) + ":");
					final Kleenean hadDelayBefore = hasDelayBefore;
					items.add(new While(c, (SectionNode) n));
					if (hadDelayBefore != Kleenean.TRUE && hasDelayBefore != Kleenean.FALSE)
						hasDelayBefore = Kleenean.UNKNOWN;
				} else if (name.equalsIgnoreCase("else")) {
					if (items.size() == 0 || !(items.get(items.size() - 1) instanceof Conditional) || ((Conditional) items.get(items.size() - 1)).hasElseClause()) {
						Skript.error("'else' has to be placed just after an 'if' or 'else if' section");
						continue;
					}
					if (Skript.debug() || n.debug())
						Skript.debug(indentation + "else:");
					final Kleenean hadDelayAfterLastIf = hasDelayBefore;
					hasDelayBefore = hadDelayBeforeLastIf;
					((Conditional) items.get(items.size() - 1)).loadElseClause((SectionNode) n);
					hasDelayBefore = hadDelayBeforeLastIf.or(hadDelayAfterLastIf.and(hasDelayBefore));
				} else if (StringUtils.startsWithIgnoreCase(name, "else if ")) {
					if (items.size() == 0 || !(items.get(items.size() - 1) instanceof Conditional) || ((Conditional) items.get(items.size() - 1)).hasElseClause()) {
						Skript.error("'else if' has to be placed just after another 'if' or 'else if' section");
						continue;
					}
					name = "" + name.substring("else if ".length());
					final Condition cond = Condition.parse(name, "can't understand this condition: '" + name + "'");
					if (cond == null)
						continue;
					if (Skript.debug() || n.debug())
						Skript.debug(indentation + "else if " + cond.toString(null, true));
					final Kleenean hadDelayAfterLastIf = hasDelayBefore;
					hasDelayBefore = hadDelayBeforeLastIf;
					((Conditional) items.get(items.size() - 1)).loadElseIf(cond, (SectionNode) n);
					hasDelayBefore = hadDelayBeforeLastIf.or(hadDelayAfterLastIf.and(hasDelayBefore.and(Kleenean.UNKNOWN)));
				} else {
					if (StringUtils.startsWithIgnoreCase(name, "if "))
						name = "" + name.substring(3);
					else {
						Scope.setLastSection(new ScopeSection((SectionNode)n));
						final Scope eff = Scope.parse(name, null); // null String so Skript won't show error if it is a condition.
						Scope.setLastSection(null);
						if (eff != null) { // If false, it will try to check as condition.
							if (Skript.debug() || n.debug())
								Skript.debug(indentation + eff.toString(null, true) + ":");
							final Kleenean hadDelayBefore = hasDelayBefore;
							items.add(eff);
							if (hadDelayBefore != Kleenean.TRUE && hasDelayBefore != Kleenean.FALSE)
								hasDelayBefore = Kleenean.UNKNOWN;
							continue;
						}
					}
					final Condition cond = Condition.parse(name, "can't understand this condition: '" + name + "'");
					if (cond == null)
						continue;
					if (Skript.debug() || n.debug())
						Skript.debug(indentation + cond.toString(null, true) + ":");
					final Kleenean hadDelayBefore = hasDelayBefore;
					hadDelayBeforeLastIf = hadDelayBefore;
					items.add(new Conditional(cond, (SectionNode) n));
					hasDelayBefore = hadDelayBefore.or(hasDelayBefore.and(Kleenean.UNKNOWN));
				}
			}
		}
		
		for (int i = 0; i < items.size() - 1; i++)
			items.get(i).setNext(items.get(i + 1));
		
		SkriptLogger.setNode(node);
		
		if (Skript.debug())
			indentation = "" + indentation.substring(0, indentation.length() - 4);
		
		return items;
	}
	
	/**
	 * For unit testing
	 * 
	 * @param node
	 * @return The loaded Trigger
	 */
	@Nullable
	static Trigger loadTrigger(final SectionNode node) {
		String event = node.getKey();
		if (event == null) {
			assert false : node;
			return null;
		}
		if (event.toLowerCase().startsWith("on "))
			event = "" + event.substring("on ".length());
		
		final NonNullPair<SkriptEventInfo<?>, SkriptEvent> parsedEvent = SkriptParser.parseEvent(event, "can't understand this event: '" + node.getKey() + "'");
		if (parsedEvent == null) {
			assert false;
			return null;
		}
		
		setCurrentEvent("unit test", parsedEvent.getFirst().events);
		try {
			return new Trigger(null, event, parsedEvent.getSecond(), loadItems(node));
		} finally {
			deleteCurrentEvent();
		}
	}
	
	public final static int loadedScripts() {
		synchronized (loadedScripts) {
			return loadedScripts.files;
		}
	}
	
	public final static int loadedCommands() {
		synchronized (loadedScripts) {
			return loadedScripts.commands;
		}
	}
	
	public final static int loadedFunctions() {
		synchronized (loadedScripts) {
			return loadedScripts.functions;
		}
	}
	
	public final static int loadedTriggers() {
		synchronized (loadedScripts) {
			return loadedScripts.triggers;
		}
	}
	
	public final static boolean isCurrentEvent(final @Nullable Class<? extends Event> event) {
		return CollectionUtils.containsSuperclass(currentEvents, event);
	}
	
	@SafeVarargs
	public final static boolean isCurrentEvent(final Class<? extends Event>... events) {
		return CollectionUtils.containsAnySuperclass(currentEvents, events);
	}
	
	/**
	 * Use this sparingly; {@link #isCurrentEvent(Class)} or {@link #isCurrentEvent(Class...)} should be used in most cases.
	 */
	@Nullable
	public static Class<? extends Event>[] getCurrentEvents() {
		return currentEvents;
	}
	
}
