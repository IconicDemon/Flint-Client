package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.SoundActivity;
import java.util.HashMap;
import java.util.Map;

/**
 * Captions for Minecraft 1.8.9's sounds, for Subtitles' Nearby Sound Activity. 1.8.9 came before Minecraft had subtitles (1.9
 * added them), so its sounds have no caption of their own: each of its sound events ({@code assets/minecraft/sounds.json},
 * 246 of them) that a later Minecraft captions is given that caption's English text, word for word ("random.chestopen" is
 * "Chest opens", "game.potion.smash" is "Bottle smashes"). Where 1.8.9 plays one sound for several things the caption says
 * only what is certain ("dig.stone" is a block broken or placed, "random.click" any click). Music, records and the menu
 * button have none, as in later versions.
 *
 * <p>Each caption also says which of Subtitles' Sound categories it answers to. 1.8.9's footsteps ({@code step.*}) are shared
 * by players and most mobs, so they count as player sounds. English only: 1.8.9's languages have no caption text to use.
 * Pure Java.
 */
public final class SoundCaptions189 {
	/** A caption, and the Sound categories switch it answers to ({@link SoundActivity#AMBIENT} and the rest). */
	public record Caption(String text, int group) {
	}

	private static final int A = SoundActivity.AMBIENT;
	private static final int B = SoundActivity.BLOCKS;
	private static final int E = SoundActivity.ENTITIES;
	private static final int C = SoundActivity.COMBAT;
	private static final int P = SoundActivity.PLAYERS;

	/** "random.bow": an arrow shot (full volume) or something thrown (half: pearls, potions, snowballs, eggs, a fishing line). */
	static final Caption ARROW_FIRED = new Caption("Arrow fired", C);
	static final Caption THROWN = new Caption("Projectile thrown", C);

	private static final Map<String, Caption> CAPTIONS = new HashMap<>(320);

	static {
		put(A, "Eerie noise", "ambient.cave.cave");
		put(A, "Rain falls", "ambient.weather.rain");
		put(A, "Thunder roars", "ambient.weather.thunder");
		put(P, "Something fell", "game.player.hurt.fall.big");
		put(P, "Something trips", "game.player.hurt.fall.small");
		put(E, "Something fell", "game.neutral.hurt.fall.big", "game.hostile.hurt.fall.big");
		put(E, "Something trips", "game.neutral.hurt.fall.small", "game.hostile.hurt.fall.small");
		put(C, "Player hurts", "game.player.hurt");
		put(C, "Player dies", "game.player.die");
		put(E, "Something hurts", "game.neutral.hurt", "game.hostile.hurt");
		put(E, "Dying", "game.neutral.die", "game.hostile.die");
		put(B, "Block broken or placed", "dig.cloth", "dig.grass", "dig.gravel", "dig.sand", "dig.snow", "dig.stone", "dig.wood");
		put(B, "Glass breaks", "dig.glass");
		put(A, "Fire crackles", "fire.fire");
		put(P, "Flint and Steel click", "fire.ignite");
		put(C, "Fireball whooshes", "item.fireCharge.use");
		put(A, "Firework blasts", "fireworks.blast", "fireworks.blast_far", "fireworks.largeBlast", "fireworks.largeBlast_far");
		put(A, "Firework launches", "fireworks.launch");
		put(A, "Firework twinkles", "fireworks.twinkle", "fireworks.twinkle_far");
		put(A, "Lava pops", "liquid.lava", "liquid.lavapop");
		put(A, "Water flows", "liquid.water");
		put(E, "Splashing", "game.neutral.swim.splash", "game.hostile.swim.splash");
		put(P, "Splashing", "game.player.swim.splash", "random.splash");
		put(P, "Swimming", "game.player.swim");
		put(E, "Swimming", "game.neutral.swim", "game.hostile.swim");
		put(E, "Minecart rolls", "minecart.base");
		put(P, "Minecart jangles", "minecart.inside");

		put(E, "Bat dies", "mob.bat.death");
		put(E, "Bat hurts", "mob.bat.hurt");
		put(E, "Bat screeches", "mob.bat.idle");
		put(E, "Bat takes off", "mob.bat.takeoff");
		put(E, "Blaze breathes", "mob.blaze.breathe");
		put(E, "Blaze dies", "mob.blaze.death");
		put(E, "Blaze hurts", "mob.blaze.hit");
		put(E, "Guardian hurts", "mob.guardian.hit", "mob.guardian.land.hit");
		put(E, "Guardian moans", "mob.guardian.idle");
		put(E, "Guardian flaps", "mob.guardian.land.idle");
		put(E, "Guardian dies", "mob.guardian.death", "mob.guardian.land.death");
		put(E, "Elder Guardian hurts", "mob.guardian.elder.hit");
		put(E, "Elder Guardian moans", "mob.guardian.elder.idle");
		put(E, "Elder Guardian dies", "mob.guardian.elder.death");
		put(E, "Elder Guardian curses", "mob.guardian.curse");
		put(C, "Guardian shoots", "mob.guardian.attack");
		put(E, "Guardian flops", "mob.guardian.flop");
		put(E, "Cat hisses", "mob.cat.hiss");
		put(E, "Cat hurts", "mob.cat.hitt");
		put(E, "Cat meows", "mob.cat.meow", "mob.cat.purreow");
		put(E, "Cat purrs", "mob.cat.purr");
		put(E, "Chicken hurts", "mob.chicken.hurt");
		put(E, "Chicken plops", "mob.chicken.plop");
		put(E, "Chicken clucks", "mob.chicken.say");
		put(E, "Cow hurts", "mob.cow.hurt");
		put(E, "Cow moos", "mob.cow.say");
		put(E, "Creeper dies", "mob.creeper.death");
		put(E, "Creeper hurts", "mob.creeper.say");
		put(C, "Creeper hisses", "creeper.primed");
		put(E, "Dragon dies", "mob.enderdragon.end");
		put(E, "Dragon growls", "mob.enderdragon.growl");
		put(E, "Dragon hurts", "mob.enderdragon.hit");
		put(E, "Dragon flaps", "mob.enderdragon.wings");
		put(E, "Enderman dies", "mob.endermen.death");
		put(E, "Enderman hurts", "mob.endermen.hit");
		put(E, "Enderman vwoops", "mob.endermen.idle");
		put(E, "Enderman teleports", "mob.endermen.portal");
		put(E, "Enderman screams", "mob.endermen.scream");
		put(E, "Enderman cries out", "mob.endermen.stare");
		put(E, "Ghast cries", "mob.ghast.affectionate_scream", "mob.ghast.moan");
		put(E, "Ghast charges", "mob.ghast.charge");
		put(C, "Ghast shoots", "mob.ghast.fireball");
		put(E, "Ghast dies", "mob.ghast.death");
		put(E, "Ghast hurts", "mob.ghast.scream");
		put(E, "Horse neighs", "mob.horse.angry", "mob.horse.idle");
		put(E, "Horse armor equips", "mob.horse.armor");
		put(E, "Horse breathes", "mob.horse.breathe");
		put(E, "Horse dies", "mob.horse.death");
		put(E, "Donkey neighs", "mob.horse.donkey.angry");
		put(E, "Donkey dies", "mob.horse.donkey.death");
		put(E, "Donkey hurts", "mob.horse.donkey.hit");
		put(E, "Donkey hee-haws", "mob.horse.donkey.idle");
		put(E, "Horse gallops", "mob.horse.gallop");
		put(E, "Horse hurts", "mob.horse.hit");
		put(E, "Horse jumps", "mob.horse.jump");
		put(E, "Horse lands", "mob.horse.land");
		put(E, "Saddle equips", "mob.horse.leather");
		put(E, "Skeleton Horse dies", "mob.horse.skeleton.death");
		put(E, "Skeleton Horse hurts", "mob.horse.skeleton.hit");
		put(E, "Skeleton Horse cries", "mob.horse.skeleton.idle");
		put(E, "Zombie Horse dies", "mob.horse.zombie.death");
		put(E, "Zombie Horse hurts", "mob.horse.zombie.hit");
		put(E, "Zombie Horse cries", "mob.horse.zombie.idle");
		put(E, "Iron Golem dies", "mob.irongolem.death");
		put(E, "Iron Golem hurts", "mob.irongolem.hit");
		put(C, "Iron Golem attacks", "mob.irongolem.throw");
		put(E, "Magma Cube squishes", "mob.magmacube.big", "mob.magmacube.small", "mob.magmacube.jump");
		put(E, "Pig dies", "mob.pig.death");
		put(E, "Pig oinks", "mob.pig.say");
		put(E, "Rabbit hurts", "mob.rabbit.hurt");
		put(E, "Rabbit squeaks", "mob.rabbit.idle");
		put(E, "Rabbit hops", "mob.rabbit.hop");
		put(E, "Rabbit dies", "mob.rabbit.death");
		put(E, "Sheep baahs", "mob.sheep.say");
		put(P, "Shears click", "mob.sheep.shear");
		put(E, "Silverfish hurts", "mob.silverfish.hit");
		put(E, "Silverfish dies", "mob.silverfish.kill");
		put(E, "Silverfish hisses", "mob.silverfish.say");
		put(E, "Skeleton dies", "mob.skeleton.death");
		put(E, "Skeleton hurts", "mob.skeleton.hurt");
		put(E, "Skeleton rattles", "mob.skeleton.say");
		put(C, "Slime attacks", "mob.slime.attack");
		put(E, "Slime squishes", "mob.slime.big", "mob.slime.small");
		put(E, "Spider dies", "mob.spider.death");
		put(E, "Spider hisses", "mob.spider.say");
		put(E, "Villager dies", "mob.villager.death");
		put(E, "Villager trades", "mob.villager.haggle");
		put(E, "Villager hurts", "mob.villager.hit");
		put(E, "Villager mumbles", "mob.villager.idle");
		put(E, "Villager disagrees", "mob.villager.no");
		put(E, "Villager agrees", "mob.villager.yes");
		put(E, "Wither dies", "mob.wither.death");
		put(E, "Wither hurts", "mob.wither.hurt");
		put(E, "Wither angers", "mob.wither.idle");
		put(C, "Wither attacks", "mob.wither.shoot");
		put(E, "Wither released", "mob.wither.spawn");
		put(E, "Wolf barks", "mob.wolf.bark");
		put(E, "Wolf dies", "mob.wolf.death");
		put(E, "Wolf growls", "mob.wolf.growl");
		put(E, "Wolf howls", "mob.wolf.howl");
		put(E, "Wolf hurts", "mob.wolf.hurt");
		put(E, "Wolf pants", "mob.wolf.panting");
		put(E, "Wolf shakes", "mob.wolf.shake");
		put(E, "Wolf whines", "mob.wolf.whine");
		put(E, "Zombie dies", "mob.zombie.death");
		put(E, "Zombie hurts", "mob.zombie.hurt");
		put(E, "Zombie infects", "mob.zombie.infect");
		put(E, "Door shakes", "mob.zombie.metal", "mob.zombie.wood");
		put(E, "Zombie Villager snuffles", "mob.zombie.remedy");
		put(E, "Zombie groans", "mob.zombie.say");
		put(E, "Zombie Villager vociferates", "mob.zombie.unfect");
		put(E, "Door breaks", "mob.zombie.woodbreak");
		put(E, "Zombie Pigman grunts", "mob.zombiepig.zpig");
		put(E, "Zombie Pigman grunts angrily", "mob.zombiepig.zpigangry");
		put(E, "Zombie Pigman dies", "mob.zombiepig.zpigdeath");
		put(E, "Zombie Pigman hurts", "mob.zombiepig.zpighurt");
		// Mobs' own footsteps.
		put(E, "Footsteps", "mob.chicken.step", "mob.cow.step", "mob.pig.step", "mob.sheep.step", "mob.silverfish.step", "mob.skeleton.step",
			"mob.spider.step", "mob.wolf.step", "mob.zombie.step", "mob.horse.soft", "mob.horse.wood", "mob.irongolem.walk");

		put(B, "Note Block plays", "note.bass", "note.bassattack", "note.bd", "note.harp", "note.hat", "note.pling", "note.snare");
		put(A, "Portal whooshes", "portal.portal");
		put(P, "Portal noise fades", "portal.travel");
		put(A, "Portal noise intensifies", "portal.trigger");
		put(B, "Anvil destroyed", "random.anvil_break");
		put(B, "Anvil landed", "random.anvil_land");
		put(B, "Anvil used", "random.anvil_use");
		put(C, "Arrow hits", "random.bowhit");
		put(P, "Item breaks", "random.break");
		put(P, "Burp", "random.burp");
		put(B, "Chest closes", "random.chestclosed");
		put(B, "Chest opens", "random.chestopen");
		put(B, "Something clicks", "random.click", "random.wood_click");
		put(B, "Door creaks", "random.door_close", "random.door_open");
		put(P, "Sipping", "random.drink");
		put(P, "Eating", "random.eat");
		put(C, "Explosion", "random.explode");
		put(A, "Something fizzes", "random.fizz");
		put(C, "TNT fizzes", "game.tnt.primed");
		put(C, "Bottle smashes", "game.potion.smash");
		put(P, "Player dings", "random.levelup");
		put(P, "Experience gained", "random.orb");
		put(P, "Item plops", "random.pop");
		put(C, "Player hit", "random.successful_hit");
		// Footsteps on blocks: players' and most mobs' alike.
		put(P, "Footsteps", "step.cloth", "step.grass", "step.gravel", "step.ladder", "step.sand", "step.snow", "step.stone", "step.wood");
		put(B, "Piston moves", "tile.piston.in", "tile.piston.out");
	}

	private SoundCaptions189() {
	}

	private static void put(int group, String text, String... names) {
		Caption caption = new Caption(text, group);
		for (String name : names) {
			if (CAPTIONS.put(name, caption) != null) {
				throw new IllegalStateException("two captions for " + name);
			}
		}
	}

	/**
	 * The caption of 1.8.9's sound {@code name} ("random.chestopen", without "minecraft:") played at {@code volume}, or null for
	 * one without (music, records, the menu's button, a sound a resource pack added). A map read; allocation-free.
	 */
	public static Caption of(String name, float volume) {
		if ("random.bow".equals(name)) {
			return volume >= 0.9F ? ARROW_FIRED : THROWN;
		}
		return CAPTIONS.get(name);
	}

	/** How many sound names have a caption ("random.bow" included). */
	static int size() {
		return CAPTIONS.size() + 1;
	}
}
