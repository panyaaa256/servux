package fi.dy.masa.servux.schematic.verifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.servux.util.data.tag.CompoundData;

/**
 * Pairs the entities a placement would spawn with the entities actually in the world, and
 * reports the ones that have no counterpart as missing.
 * <p>
 * The rules are Litematica's own ({@code SchematicVerifier.verifyEntitiesInBox()} and
 * {@code findMatchingClientEntity()}), so that a server run and a client run of the same
 * placement agree on what counts as missing:
 * <ul>
 *   <li>Only entities that stay where they were put are checked. Mobs wander and items,
 *       XP orbs and projectiles are transient, so their positions mean nothing; armor
 *       stands are living entities but do not move, so they are checked.</li>
 *   <li>A match is the closest world entity of the same type within the tolerance.</li>
 *   <li>Each world entity can stand in for one schematic entity only, so two minecarts
 *       placed in the same spot each need their own.</li>
 * </ul>
 * One matcher serves one whole run: the set of world entities already claimed has to span
 * every chunk and every placement the run covers.
 * <p>
 * Unlike the client there is no "unseen" outcome - the server can see every entity in a
 * loaded chunk, so not finding one means that it is not there.
 */
public class VerifyEntityMatcher
{
	/** The tolerance a run started from a command uses: Litematica's own default. */
	public static final double DEFAULT_TOLERANCE = 0.1;
	/** Litematica's upper bound for {@code verifierEntityPositionTolerance}. */
	public static final double MAX_TOLERANCE = 8.0;

	private final double tolerance;
	private final Set<UUID> claimedWorldEntities = new HashSet<>();
	/** Whether an entity type is one that gets checked; see {@link #isVerifiableType}. */
	private final Object2BooleanOpenHashMap<EntityType<?>> verifiableTypes = new Object2BooleanOpenHashMap<>();

	public VerifyEntityMatcher(double tolerance)
	{
		this.tolerance = Math.max(0.0, Math.min(tolerance, MAX_TOLERANCE));
	}

	public double getTolerance()
	{
		return this.tolerance;
	}

	/**
	 * Checks one schematic entity against the world.
	 *
	 * @param worldPos where the placement would put it
	 * @param nbt      the schematic's entity data; only read
	 */
	public void verify(ServerLevel world, Vec3 worldPos, CompoundData nbt, VerifyResult result)
	{
		Identifier id = Identifier.tryParse(nbt.getStringOrDefault("id", ""));
		EntityType<?> type = id != null ? BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) : null;

		if (type == null || !this.isVerifiableType(world, type))
		{
			return;
		}

		Entity match = this.findMatch(world, type, worldPos);

		if (match != null)
		{
			this.claimedWorldEntities.add(match.getUUID());
		}
		else
		{
			result.addMissingEntity(id.toString(), worldPos);
		}
	}

	/**
	 * The closest unclaimed world entity of the given type within the tolerance, the same
	 * search Litematica's {@code findMatchingClientEntity()} does against the client world.
	 */
	@Nullable
	private Entity findMatch(ServerLevel world, EntityType<?> type, Vec3 pos)
	{
		AABB searchBox = new AABB(pos, pos).inflate(Math.max(this.tolerance, 0.001));
		List<Entity> candidates = world.getEntities((Entity) null, searchBox,
		                                            e -> e.getType() == type &&
		                                                 !this.claimedWorldEntities.contains(e.getUUID()) &&
		                                                 e.position().distanceTo(pos) <= this.tolerance);

		Entity closest = null;
		double closestDistSq = Double.MAX_VALUE;

		for (Entity candidate : candidates)
		{
			double distSq = candidate.position().distanceToSqr(pos);

			if (distSq < closestDistSq)
			{
				closestDistSq = distSq;
				closest = candidate;
			}
		}

		return closest;
	}

	/**
	 * Litematica's {@code isVerifiableEntity()}, which is written against entity classes.
	 * A schematic entity is only data here, so the class is found by creating one throwaway
	 * instance per type - never added to the world - and the answer is cached.
	 */
	private boolean isVerifiableType(ServerLevel world, EntityType<?> type)
	{
		if (this.verifiableTypes.containsKey(type))
		{
			return this.verifiableTypes.getBoolean(type);
		}

		boolean verifiable;

		try
		{
			Entity probe = type.create(world, EntitySpawnReason.LOAD);
			verifiable = probe != null && isVerifiableEntity(probe);
		}
		catch (Exception e)
		{
			verifiable = false;
		}

		this.verifiableTypes.put(type, verifiable);

		return verifiable;
	}

	/** Litematica's {@code SchematicVerifier.isVerifiableEntity()}, verbatim. */
	public static boolean isVerifiableEntity(Entity entity)
	{
		if (entity instanceof LivingEntity && (entity instanceof ArmorStand) == false)
		{
			return false;
		}

		return (entity instanceof ItemEntity ||
		        entity instanceof ExperienceOrb ||
		        entity instanceof Projectile) == false;
	}
}
