
// contains numerical patterns

PatternProxy : Pattern {
	var <pattern;
	var <>clock, <>quant, <envir; 	
						// quant new pattern insertion. can be [quant, offset]
						// in EventPatternProxy it can be [quant, offset, onset]
	
	classvar <>defaultQuant;
	
	// basicNew should be used for instantiation: *new is used in Pdef/Tdef/Pdefn
	*basicNew { arg source;
		^super.new.init(source)
	}
		
	*default { ^1 } // safe for duration patterns
	
	init { arg src;
		clock = TempoClock.default; 
		quant = this.class.defaultQuant;
		this.source = src ?? { this.class.default }
	}
	
	constrainStream { ^pattern.asStream }
	
	source_ { arg obj; 
		var pat;
		pat = if(obj.isKindOf(Function)) {
			Proutine { arg inval; loop { inval = embedInStream(obj.valueEnvir) } };
		} { obj };
		if(envir.notNil) { pat = Penvir(envir, pat, envir[\isolate] ? false) };
		
		this.sched { pattern = pat } 
	}
	source { ^pattern }
	
	pattern_ { arg pat; this.source_(pat) }
	offset_ { arg val; quant = quant.instil(1, val) }
	offset { arg val; ^quant.obtain(1) }

	envir_ { arg argEnvir;
		envir = argEnvir; 
		this.source = this.source;
	}
	
	set { arg ... args; 
		if(envir.isNil) { this.envir = () };
		args.pairsDo { arg key, val; envir.put(key, val) };
	}
	
	get { arg key;
		^if(envir.notNil) { envir[key] } { nil };
	}
	
		
	embedInStream { arg inval;
		var pat, stream, outval;
		pat = pattern;
		
		stream = pattern.asStream;
		while {
			if((pat !== pattern)) {
						pat = pattern;
						stream = this.constrainStream(stream);
			};
			outval = stream.next(inval);
			outval.notNil
		}{
			inval = outval.yield;
		}
		^inval
	}
	
	sched { arg func;
		if(quant.isNil) 
			{ func.value } 
			{ clock.schedAbs(quant.nextTimeOnGrid(clock), { func.value; nil }) }
	}

	storeArgs { ^[pattern] }
	
	
	/////////////////////////
	// these following methods are factored out for the benefit of subclasses
	// they only work for Pdef/Tdef/Pdefn
	
	*new { arg key, item;
		var res;
		res = this.at(key);
		if(res.isNil) {
				res = this.basicNew(item).key_(key);
				this.put(key, res);
		} {
				if(item.notNil) { res.source = item }
		}
		^res
	
	}

	
	*cmdPeriod { this.all.do { arg item; item.stop } }
	
	*removeAll { 
		this.all.do { arg pat; pat.stop }; 
		this.all.makeEmpty; 
	}
	
	*at { ^nil }
	*put {}
	key_ {}
	
	clear { this.class.all.removeAt(this.key).stop }
	
	repositoryArgs { ^[this.key, this.source] }
	
	*postRepository { arg keys, stream;
		keys = keys ?? { this.all.keys };
		stream = stream ? Post;
		keys.do { arg key; 
			var item;
			item = this.all[key];
			if(item.notNil and: { item.source !== this.default }) {
				stream << item.class.name << "(" <<<* item.repositoryArgs << ")";
				if(item.envir.notNil and: { item.envir.notEmpty }) {
					stream << ".set(" <<<* item.envir.asKeyValuePairs << ")"
				};
				
				stream << ";\n"
			};
		};
	}
	////////////////
	
}

Pdefn : PatternProxy {
	var <>key;
	classvar <>all;
	
	*initClass { 
		all = IdentityDictionary.new;
	}
	*at { arg key;
		^all.at(key);
	}
	*put { arg key, pattern;
		all.put(key, pattern);
	}
	map { arg ... args;
		if(envir.isNil) { this.envir = () };
		args.pairsDo { |key, name| envir.put(key, Pdefn(name)) }
	}
	storeArgs { ^[key] } // assume it was created globally
	
}


// contains time patterns (tasks)

TaskProxy : PatternProxy {
	var <isPlaying=false, <source;
	var <player, <>playQuant;
	classvar <>defaultQuant=1.0;
	
		
	source_ { arg obj;
			pattern = if(obj.isKindOf(Function)) {
				// this error handling only helps if error is not in substream
				Prout { |inval|
					try { 
						obj.value
					} { |error|
						player.removedFromScheduler;
						error.throw; 
					}
				}
			}{ 
				obj 
			};
			if(envir.notNil) { pattern = Penvir(envir, pattern, envir[\isolate] ? false) };
			this.wakeUp;
			source = obj;
	}
	
		
	wakeUp { if(isPlaying and: { player.isPlaying.not }) { this.play(quant:playQuant) } }
	

	*default { ^{ |inval| inval.yield } }
	
	
	constrainStream { arg str;
		^if(quant.notNil and: { str.notNil }) {
			Pseq([
				Pconst(clock.timeToNextBeat(quant), str, 0.001),
				pattern
			])
		} { pattern }.asStream
	}
	
	playOnce { arg argClock, doReset = (false), quant;
		clock = argClock ? clock;
		^PauseStream.new(this.asStream).play(clock, doReset, quant ? this.quant)
	}
	
	play { arg argClock, doReset = false, quant;
		isPlaying = true;
		playQuant = quant;
		if(player.isPlaying.not) { player = this.playOnce(argClock, doReset, quant) }
	}
	
	fork { arg clock, quant;
		^this.asStream.play(clock ? thisThread.clock, quant)
	}
	
	stop { player.stop; isPlaying = false }
	
	pause { if(player.notNil) { this.sched { player.pause } } }
	resume { if(player.notNil) { this.sched { player.resume } } }

	
		
	storeArgs { ^[source] }
}



Tdef : TaskProxy {
	var <>key;
	classvar <>all;
	
	
	*initClass { 
		all = IdentityDictionary.new;
		CmdPeriod.add(this); 
	}
	*at { arg key;
		^all.at(key);
	}
	*put { arg key, pattern;
		all.put(key, pattern);
	}

	storeArgs { ^[key] }

}



// contains event patterns

EventPatternProxy : TaskProxy {
	var <>fadeTime;
	classvar <>defaultQuant=1.0;
	
	source_ { arg item;
		if(item.isKindOf(Function)) // allow functions to be passed in
			{ source = item; pattern = PlazyEnvir(item) } 
			{ pattern = source = item };
		if(envir.notNil) { pattern = pattern <> envir };
		this.wakeUp;
	}
	
	*default { ^Pn(Event.silent,1) }


	constrainStream { arg str;
		var delta, tolerance, new, quantVal, catchUp, deltaTillCatchUp, forwardTime;
		^if(quant.notNil) {
			
			if(quant.isSequenceableCollection) {
				quantVal = quant[0];
				catchUp = quant[2];
			} {
				quantVal = quant;
			};
			
			delta = clock.timeToNextBeat(quant);
			tolerance = quantVal % delta % 0.125;
			if(catchUp.notNil) {
				deltaTillCatchUp = clock.timeToNextBeat(catchUp);
				new = pattern.asStream;
				forwardTime = quantVal - delta + deltaTillCatchUp;
				delta = new.fastForward(forwardTime, tolerance) + deltaTillCatchUp;
			} {
				new = pattern
			};
			
			if(fadeTime.isNil) {
				if(delta < 0.01) { 
					Routine({ arg inval;
						Event.silent(delta).yield;
						new.embedInStream(inval)
					})
				}{	
					Pseq([ Pfindur(delta, str, tolerance), new ])
					
				}
			}{
				
				Ppar([
					PfadeOut(str, fadeTime, delta, tolerance),
					PfadeIn(new, fadeTime, delta, tolerance)
				])
			}
		} { pattern }.asStream
	}
	
	outset_ { arg val; quant = quant.instill(2, val) }
	outset { arg val; ^quant.obtain(2) }
	
	// branching from another thread
	
	fork { arg argClock, quant, protoEvent; // usual fork arg order: clock, quant, ...
		argClock = argClock ? thisThread.clock;
		^EventStreamPlayer(this.asStream, protoEvent).play(argClock, true, quant)
	}
	
	// playing one instance //
	
	playOnce { arg argClock, protoEvent, quant;
		^this.fork(argClock ? clock, quant ? this.quant, protoEvent)
	}
	
	// start playing //
	
	play { arg argClock, protoEvent, quant;
		isPlaying = true;
		if(player.isPlaying.not) {
			clock = argClock ? TempoClock.default;
			player = EventStreamPlayer(this.asStream, protoEvent);
			player.play(clock, true, quant ? this.quant)
		}
	}
	
	
	storeArgs { ^[source] }
	
}

Pdef : EventPatternProxy {
	var <>key;
	
	classvar <>all;	
				
	storeArgs { ^[key] }
	
	*at { arg key;
		^all.at(key);
	}
	*put { arg key, pattern;
		all.put(key, pattern);
	}
	map { arg ... args;
		if(envir.isNil) { this.envir = () };
		args.pairsDo { |key, name| envir.put(key, Pdefn(name)) }
	}
	*initClass {
		var phraseEventFunc;
		CmdPeriod.add(this);
		
		all = IdentityDictionary.new; 
		Class.initClassTree(Event);
		
		phraseEventFunc = {
			var pat, event, outerEvent, recursionLevel, instrument, embeddingLevel, freq, rest;
			
				embeddingLevel = ~embeddingLevel ? 0; // infinite recursion catch
				freq = ~freq.value;
				rest = freq.isKindOf(Symbol); // check for outer rests
				if(rest) { ~freq = freq };
				pat = all.at(~instrument);
				
				if(pat.notNil and: { embeddingLevel < 8 })
				{
					pat = pat.pattern; // optimization. outer pattern takes care for replacement
					// preserve information from outer pattern, but not delta.
					outerEvent = currentEnvironment.copy;
					recursionLevel = ~recursionLevel;
					if(recursionLevel.notNil) {
						if(recursionLevel > 0) {
							// in recursion, some inner values have to be overridden
							instrument = ~instrument;
							pat = pat.collect { |inval|
									inval.use {
											~instrument = instrument;
											~parent = outerEvent;
											~recursionLevel = recursionLevel - 1;
										};
										inval
							};
						} {
							// play pattern in the ordinary way
							~type = \note;
						};
					} {	// avoid recursion, if instrument not set.
						outerEvent.put(\embeddingLevel, embeddingLevel + 1);
						outerEvent.parent_(Event.parentEvents.default);
					};
					pat = Pfindur(~sustain.value, pat);
					outerEvent.put(\delta, nil); // block delta modification by Ppar
					outerEvent.put(\instrument, ~synthDef ? \default);
				
					pat.play(thisThread.clock, outerEvent, 0.0);
				} {
					~type = \note;
					~play.value;
				}
		
		};
		
		Event.parentEvents.default.eventTypes.put(\phrase, phraseEventFunc);
	}
	
}



PbindProxy : Pattern {
	var <>pairs, <source;
	
	*new { arg ... pairs;
		^super.newCopyArgs(pairs).init
	}
	init {
		forBy(0, pairs.size-1, 2) { arg i;
			pairs[i+1] = PatternProxy.basicNew(pairs[i+1])
		};
		source = EventPatternProxy.basicNew(Pbind(*pairs));
	}
	embedInStream { arg inval;
		^source.embedInStream(inval)
	}
	find { arg key; // optimize later maybe.
		pairs.pairsDo { |u,x,i| if(u === key) { ^i } }; ^nil
	}
	quant_ { arg val;
		pairs.pairsDo { arg key, item; item.quant = val }; // maybe use ref later
		source.quant = val;
	}
	quant { ^source.quant }
	envir { ^source.envir }
	envir_ { arg envir; source.envir_(envir) }
	
	at { arg key; var i; i = this.find(key); ^if(i.notNil) { pairs[i+1] } { nil } }
	
	// does not yet work with adding arrayed keys/values
	set { arg ... args; // key, val ...
		var changedPairs=false, quant;
		quant = this.quant;
		args.pairsDo { |key, val|
			var i, remove;
			i = this.find(key);
			if(i.notNil)
			{ 
				if(val.isNil) {
					pairs.removeAt(i);
					pairs.removeAt(i);
					changedPairs = true;
				}{
					pairs[i+1].source = val
				};
			}{ 
				pairs = pairs ++ [key, PatternProxy.basicNew(val).quant_(quant)];
				changedPairs = true;
			};
		
		};
		if(changedPairs) { source.source =  Pbind(*pairs) };
		
	}
	
	//source_ { arg pat; source.source = pat }
	storeArgs { ^pairs.collect(_.source) }
}


Pbindef : Pdef {
	*new { arg ... pairs;
		var key, pat, src;
		key = pairs.removeAt(0);
		pat = super.new(key);
		src = pat.source;
		if(pairs.isEmpty.not) {
			if(src.class === PbindProxy) {
				src.set(*pairs);
				pat.wakeUp;
			} {
				if(src.isKindOf(Pbind)) 
				{
					src.patternpairs.pairsDo { |key, pat|
						if(pairs.includes(key).not) { 
							pairs = pairs.add(key); 
							pairs = pairs.add(pat);
						}
					}
				};
				
				src = PbindProxy.new(*pairs).quant_(pat.quant);
				pat.source = src
			};
		};
		
		^pat
		
	}
	storeArgs { ^[key]++pattern.storeArgs }
	repositoryArgs { ^this.storeArgs }
	quant_ { arg val; super.quant = val; source.quant = val }
}




// general purpose lookup stream

Pdict : Pattern {
	var <>dict, <>which, <>repeats, <>default;
	*new { arg dict, which, repeats=inf, default;
		^super.newCopyArgs(dict, which, repeats, default);
	}
	
	embedInStream { arg inval;
		var keyStream, key;
		keyStream = which.asStream;
		repeats.value.do({
			key = keyStream.next;
			if(key.isNil) { ^inval };
			inval = (dict.at(key) ? default).embedInStream(inval);
		});
		^inval
	}
}

