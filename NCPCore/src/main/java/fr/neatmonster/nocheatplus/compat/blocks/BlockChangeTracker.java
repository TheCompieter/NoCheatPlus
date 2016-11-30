/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.compat.blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.material.Directional;
import org.bukkit.material.Door;
import org.bukkit.material.MaterialData;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.components.location.IGetPosition;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.logging.Streams;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;
import fr.neatmonster.nocheatplus.utilities.TickTask;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordHashMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.LinkedCoordHashMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.LinkedCoordHashMap.MoveOrder;
import fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache.IBlockCacheNode;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

/**
 * Keep track of block changes, to allow mitigation of false positives. Think of
 * pistons, falling blocks, digging, block placing, explosions, vegetables
 * growing, all sorts of doors, plugins changing blocks and so on. This is
 * needed not only for elevator and parkour designs, but also to prevent piston
 * based trap designs, which could lead to victims continuously violating moving
 * checks.
 * <hr>
 * In general we assume that at the time of adding a block change entry, the
 * block has not yet been changed, so we access the "old state" at that point of
 * time. If needed, a method allowing to specify the old state explicitly will
 * be added.
 * 
 * @author asofold
 *
 */
public class BlockChangeTracker {
    /** These blocks certainly can't be pushed nor pulled. */
    public static long F_MOVABLE_IGNORE = BlockProperties.F_LIQUID;
    /** These blocks might be pushed or pulled. */
    public static long F_MOVABLE = BlockProperties.F_GROUND | BlockProperties.F_SOLID;

    public static enum Direction {
        NONE,
        X_POS,
        X_NEG,
        Y_POS,
        Y_NEG,
        Z_POS,
        Z_NEG;

        public static Direction getDirection(final BlockFace blockFace) {
            final int x = blockFace.getModX();
            if (x == 1) {
                return X_POS;
            }
            else if (x == -1) {
                return X_NEG;
            }
            final int y = blockFace.getModY();
            if (y == 1) {
                return Y_POS;
            }
            else if (y == -1) {
                return Y_NEG;
            }
            final int z = blockFace.getModZ();
            if (z == 1) {
                return Z_POS;
            }
            else if (z == -1) {
                return Z_NEG;
            }
            return NONE;
        }

    }

    /**
     * Count active block changes per chunk/thing.
     * 
     * @author asofold
     *
     */
    public static class ActivityNode {
        public int count = 0;
    }

    public static class WorldNode {
        // TODO: private + access methods.
        /*
         * TODO: A coarse rectangle or cuboid based approach, Allowing fast
         * exclusion check for moves (needs access methods for everything then).
         * Could merge with the per-block map, similar to WorldGuard.
         */
        /*
         * TODO: Consider a filter mechanism for player activity by chunks or
         * chunk sections (some margin, only add if activity, let expire by
         * tick). Only add blocks if players could reach the location.
         */

        /**
         * Count active block change entries per chunk (chunk size and access
         * are handled elsewhere, except for clear).
         */
        public final CoordMap<ActivityNode> activityMap = new CoordHashMap<ActivityNode>();
        /** Directly map to individual blocks. */
        public final LinkedCoordHashMap<LinkedList<BlockChangeEntry>> blocks = new LinkedCoordHashMap<LinkedList<BlockChangeEntry>>();
        /** Tick of last change. */
        public int lastChangeTick = 0;

        /** Total number of BlockChangeEntry instances. */
        public int size = 0;

        public final UUID worldId;

        public WorldNode(UUID worldId) {
            this.worldId = worldId;
        }

        public void clear() {
            activityMap.clear();
            blocks.clear();
            size = 0;
        }

        /**
         * Get an activity node for the given block coordinates at the given
         * resolution. If no node is there, a new one will be created.
         * 
         * @param x
         * @param y
         * @param z
         * @param activityResolution
         * @return
         */
        public ActivityNode getActivityNode(int x, int y, int z, final int activityResolution) {
            x /= activityResolution;
            y /= activityResolution;
            z /= activityResolution;
            ActivityNode node = activityMap.get(x, y, z);
            if (node == null) {
                node = new ActivityNode();
                activityMap.put(x, y, z, node);
            }
            return node;
        }

        public void removeActivityNode(final int x, final int y, final int z, final int activityResolution) {
            activityMap.remove(x / activityResolution, y / activityResolution, z / activityResolution);
        }

    }

    /**
     * Record a state of a block.
     * 
     * @author asofold
     *
     */
    public static class BlockChangeEntry {

        // TODO: Might implement IBlockPosition.

        public final long id;
        public final int tick, x, y, z;
        public final Direction direction;
        public final IBlockCacheNode previousState;
        /**
         * The tick value of the next entry, allowing to determine an interval
         * of validity for this state.
         */
        public int nextEntryTick = -1;

        /**
         * A block change entry.
         * 
         * @param id
         * @param tick
         * @param x
         * @param y
         * @param z
         * @param direction
         *            Moving direction, NONE for none.
         * @param previousState
         *            State of the block before changes may have happened. Pass
         *            null to ignore.
         */
        public BlockChangeEntry(long id,  int tick, int x, int y, int z, 
                Direction direction, IBlockCacheNode previousState) {
            this.id = id;
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.direction = direction;
            this.previousState = previousState;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == null || !(obj instanceof BlockChangeEntry)) {
                return false;
            }
            final BlockChangeEntry other = (BlockChangeEntry) obj;
            return id == other.id && tick == other.tick 
                    && x == other.x && z == other.z && y == other.y 
                    && direction == other.direction;
        }

        // Might follow: Id, data, block shape. Convenience methods for testing.

    }

    /**
     * Simple class for helping with query functionality. Reference a
     * BlockChangeEntry and contain more information, such as validity for
     * further use/effects. This is meant for storing the state of last-consumed
     * block change entries for a context within some data.
     * 
     * @author asofold
     *
     */
    public static class BlockChangeReference {

        /*
         * TODO: public BlockChangeEntry firstUsedEntry = null; // Would the
         * span suffice? Consider using span + timing or just the span during
         * one check covering multiple blocks.
         */

        /** First used (oldest) entry during checking. */
        public BlockChangeEntry firstSpanEntry = null;
        /** Last used (newest) entry during checking. */
        public BlockChangeEntry lastSpanEntry = null;

        /**
         * Last used block change entry, set after a complete iteration of
         * checking, update with updateFinal.
         */
        public BlockChangeEntry lastUsedEntry = null;

        /**
         * Indicate if the timing of the last entry is still regarded as valid.
         */
        /*
         * TODO: Subject to change, switching to tick rather than id (ids can be
         * inverted, thus lock out paths).
         */
        public boolean valid = false;

        /**
         * Check if this reference can be updated with the given entry,
         * considering set validity information. By default, the given tick
         * either must be greater than the stored one, or the tick are the same
         * and valid is set to true. The internal state is not changed by
         * calling this.
         * 
         * @param entry
         * @return
         */
        public boolean canUpdateWith(final BlockChangeEntry entry) {
            // Love access methods: return this.lastUsedEntry == null || entry.id > this.lastUsedEntry.id || entry.id == this.lastUsedEntry.id && valid;
            // TODO: There'll be a span perhaps.
            /*
             * Using ticks seems more appropriate, as ids are not necessarily
             * ordered in a relevant way, if they reference the same tick. Even
             * one tick may be too narrow.
             */
            return this.lastUsedEntry == null || entry.tick > this.lastUsedEntry.tick || entry.tick == this.lastUsedEntry.tick && valid;
        }

        /**
         * Update the span during checking. Ensure to test canUpdateWith(entry)
         * before calling this.
         * 
         * @param entry
         */
        public void updateSpan(final BlockChangeEntry entry) {
            if (firstSpanEntry == null || entry.id < firstSpanEntry.id) {
                firstSpanEntry = entry;
            }
            if (lastSpanEntry == null || entry.id > lastSpanEntry.id) {
                lastSpanEntry = entry;
            }
        }

        /**
         * Update lastUsedEntry by the set span, assuming <i>to</i> to be the
         * move end-point to continue from next time. This is meant to finalize
         * prepared changes/span for use with the next move.
         * 
         * @param to
         *            If not null, allows keeping the latest entry valid, if
         *            intersecting with the bounding box of <i>to</i>.
         */
        public void updateFinal(final RichBoundsLocation to) {
            if (firstSpanEntry == null) {
                return;
            }
            // TODO: Consider a span margin, for which we set last used to first span.
            /*
             * TODO: What with latest entries, that stay valid until half round
             * trip time? Should perhaps keep validity also if entries are the
             * latest ones, needs updating in span already - can/should do
             * without bounds?
             */
            if (lastSpanEntry != null && (lastUsedEntry == null || lastSpanEntry.id > lastUsedEntry.id)) {
                lastUsedEntry = lastSpanEntry;
                if (to != null && to.isBlockIntersecting(lastSpanEntry.x, lastSpanEntry.y, lastSpanEntry.z)) {
                    valid = true;
                }
                else {
                    valid = false;
                }
            }
            firstSpanEntry = lastSpanEntry = null;
        }

        /**
         * Retrieve a shallow copy of this object.
         * 
         * @return
         */
        public BlockChangeReference copy() {
            final BlockChangeReference copy = new BlockChangeReference();
            copy.firstSpanEntry = this.firstSpanEntry;
            copy.lastSpanEntry = this.lastSpanEntry;
            copy.lastUsedEntry = this.lastUsedEntry;
            copy.valid = this.valid;
            return copy;
        }

        public void clear() {
            firstSpanEntry = lastSpanEntry = lastUsedEntry = null;
            valid = false;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == null || !(obj instanceof BlockChangeReference)) {
                return false;
            }
            final BlockChangeReference other = (BlockChangeReference) obj;
            return valid == other.valid && 
                    (lastUsedEntry != null && lastUsedEntry.equals(other.lastUsedEntry) 
                    || lastUsedEntry == null && other.lastUsedEntry == null)
                    && (firstSpanEntry != null && firstSpanEntry.equals(other.firstSpanEntry) 
                    || firstSpanEntry == null && other.firstSpanEntry == null)
                    && (lastSpanEntry != null && lastSpanEntry.equals(other.lastSpanEntry) 
                    || lastSpanEntry == null && other.lastSpanEntry == null)
                    ;
        }

    }

    public static class BlockChangeListener implements Listener {
        private final BlockChangeTracker tracker;
        private final boolean retractHasBlocks;
        private boolean enabled = true;
        private final Set<Material> redstoneMaterials = new HashSet<Material>();

        public BlockChangeListener(final BlockChangeTracker tracker) {
            this.tracker = tracker;
            if (ReflectionUtil.getMethodNoArgs(BlockPistonRetractEvent.class, "getBlocks") == null) {
                retractHasBlocks = false;
                NCPAPIProvider.getNoCheatPlusAPI().getLogManager().info(Streams.STATUS, "Assume legacy piston behavior.");
            }
            else {
                retractHasBlocks = true;
            }
            // TODO: Make an access method to test this/such in BlockProperties!
            for (Material material : Material.values()) {
                if (material.isBlock()) {
                    final String name = material.name().toLowerCase();
                    if (name.indexOf("door") >= 0 || name.indexOf("gate") >= 0) {
                        redstoneMaterials.add(material);
                    }
                }
            }
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        private BlockFace getDirection(final Block pistonBlock) {
            final MaterialData data = pistonBlock.getState().getData();
            if (data instanceof Directional) {
                Directional directional = (Directional) data;
                return directional.getFacing();
            }
            return null;
        }

        /**
         * Get the direction, in which blocks are or would be moved (towards the piston).
         * 
         * @param pistonBlock
         * @param eventDirection
         * @return
         */
        private BlockFace getRetractDirection(final Block pistonBlock, final BlockFace eventDirection) {
            // Tested for pistons directed upwards.
            // TODO: Test for pistons directed downwards, N, W, S, E.
            // TODO: distinguish sticky vs. not sticky.
            final BlockFace pistonDirection = getDirection(pistonBlock);
            if (pistonDirection == null) {
                return eventDirection;
            }
            else {
                return eventDirection.getOppositeFace();
            }
        }

        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        public void onPistonExtend(final BlockPistonExtendEvent event) {
            if (!enabled) {
                return;
            }
            final BlockFace direction = event.getDirection();
            //DebugUtil.debug("EXTEND event=" + event.getDirection() + " piston=" + getDirection(event.getBlock()));
            tracker.addPistonBlocks(event.getBlock().getRelative(direction), direction, event.getBlocks());
        }

        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        public void onPistonRetract(final BlockPistonRetractEvent event) {
            if (!enabled) {
                return;
            }
            final List<Block> blocks;
            if (retractHasBlocks) {
                // TODO: Legacy: Set flag in constructor (getRetractLocation).
                blocks = event.getBlocks();
            }
            else {
                // TODO: Use getRetractLocation.
                @SuppressWarnings("deprecation")
                final Location retLoc = event.getRetractLocation();
                if (retLoc == null) {
                    blocks = null;
                }
                else {
                    final Block retBlock = retLoc.getBlock();
                    final long flags = BlockProperties.getBlockFlags(retBlock.getType());
                    if ((flags & F_MOVABLE_IGNORE) == 0L && (flags & F_MOVABLE) != 0L) {
                        blocks = new ArrayList<Block>(1);
                        blocks.add(retBlock);
                    }
                    else {
                        blocks = null;
                    }
                }
            }
            // TODO: Special cases (don't push upwards on retract, with the resulting location being a solid block).
            final Block pistonBlock = event.getBlock();
            final BlockFace direction = getRetractDirection(pistonBlock, event.getDirection());
            //DebugUtil.debug("RETRACT event=" + event.getDirection() + " piston=" + getDirection(event.getBlock()) + " decide=" + getRetractDirection(event.getBlock(),  event.getDirection()));
            tracker.addPistonBlocks(pistonBlock.getRelative(direction.getOppositeFace()), direction, blocks);
        }

        //        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        //        public void onBlockPhysics (final BlockPhysicsEvent event) {
        //            if (!enabled) {
        //                return;
        //            }
        //            // TODO: Fine grained enabling state (pistons, doors, other).
        //            final Block block = event.getBlock();
        //            if (block == null || !physicsMaterials.contains(block.getType())) {
        //                return;
        //            }
        //            // TODO: MaterialData -> Door, upper/lower half needed ?
        //            tracker.addBlocks(block); // TODO: Skip too fast changing states?
        //            DebugUtil.debug("BlockPhysics: " + block); // TODO: REMOVE
        //        }

        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        public void onBlockRedstone(final BlockRedstoneEvent event) {

            if (!enabled) {
                return;
            }
            // TODO: Fine grained enabling state (pistons, doors, other).
            final Block block = event.getBlock();
            // TODO: Abstract method for a block and a set of materials (redstone, interact, ...).
            if (block == null || !redstoneMaterials.contains(block.getType())) {
                return;
            }
            // TODO: MaterialData -> Door, upper/lower half.
            final MaterialData materialData = block.getState().getData();
            if (materialData instanceof Door) {
                final Door door = (Door) materialData;
                final Block otherBlock = block.getRelative(door.isTopHalf() ? BlockFace.DOWN : BlockFace.UP);
                /*
                 * TODO: Double doors... detect those too? Is it still more
                 * efficient than using BlockPhysics with lazy delayed updating
                 * (TickListener...).
                 */
                if (redstoneMaterials.contains(otherBlock.getType())) {
                    tracker.addBlocks(block, otherBlock);
                    // DebugUtil.debug("BlockRedstone door: " + block + " / " + otherBlock); // TODO: REMOVE
                    return;
                }
            }
            // Only the single block remains.
            tracker.addBlocks(block);
            // DebugUtil.debug("BlockRedstone: " + block); // TODO: REMOVE
        }

        // TODO: Falling blocks (physics?). 

    }

    /** Change id/count, increasing with each entry added internally. */
    private long maxChangeId = 0;

    /** Global maximum age for entries, in ticks. */
    private int expirationAgeTicks = 80;
    /** Size at which entries get skipped, per world node. */
    private int worldNodeSkipSize = 500;

    private int activityResolution = 32; // TODO: getter/setter/config.

    /**
     * Store the WorldNode instances by UUID, containing the block change
     * entries (and filters). Latest entries must be sorted to the end.
     */
    private final Map<UUID, WorldNode> worldMap = new LinkedHashMap<UUID, BlockChangeTracker.WorldNode>();

    /** Use to avoid duplicate entries with pistons. Always empty after processing. */
    private final Set<Block> processBlocks = new LinkedHashSet<Block>();

    /** Ensure to set from extern. */
    private IGenericInstanceHandle<BlockCache> blockCacheHandle = null;

    /*
     * TODO: Consider tracking regions of player activity (chunk sections, with
     * a margin around the player) and filter.
     */

    /**
     * Process the data, as given by a BlockPistonExtendEvent or
     * BlockPistonRetractEvent.
     * 
     * @param pistonBlock
     *            This block is added directly, unless null.
     * @param blockFace
     * @param movedBlocks
     *            Unless null, each block and the relative block in the given
     *            direction (!) are added.
     */
    public void addPistonBlocks(final Block pistonBlock, final BlockFace blockFace, final List<Block> movedBlocks) {
        checkProcessBlocks(); // TODO: Remove, once sure, that processing never ever generates an exception.
        final int tick = TickTask.getTick();
        final World world = pistonBlock.getWorld();
        final WorldNode worldNode = getOrCreateWorldNode(world, tick);
        final long changeId = ++maxChangeId;
        // Avoid duplicates by adding to a set.
        if (pistonBlock != null) {
            processBlocks.add(pistonBlock);
        }
        if (movedBlocks != null) {
            for (final Block movedBlock : movedBlocks) {
                processBlocks.add(movedBlock);
                processBlocks.add(movedBlock.getRelative(blockFace));
            }
        }
        // Process queued blocks.
        final BlockCache blockCache = blockCacheHandle.getHandle();
        blockCache.setAccess(world); // Assume all users always clean up after use :).
        for (final Block block : processBlocks) {
            addPistonBlock(changeId, tick, worldNode, block.getX(), block.getY(), block.getZ(), 
                    blockFace, blockCache);
        }
        blockCache.cleanup();
        processBlocks.clear();
    }

    /**
     * Add a block moved by a piston (or the piston itself).
     * 
     * @param changeId
     * @param tick
     * @param worldNode
     * @param x
     * @param y
     * @param z
     * @param blockFace
     * @param blockCache
     *            For retrieving the current block state.
     */
    private void addPistonBlock(final long changeId, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final BlockFace blockFace, final BlockCache blockCache) {
        // TODO: A filter for regions of player activity.
        // TODO: Test which ones can actually move a player (/how).
        // Add this block.
        addBlockChange(changeId, tick, worldNode, x, y, z, Direction.getDirection(blockFace), 
                blockCache.getOrCreateBlockCacheNode(x, y, z, true));
    }

    /**
     * Add blocks as neutral past states (no moving direction). All blocks are
     * to be in the same world (no consistency checks!), the world of the first
     * block is used.
     * 
     * @param blocks
     *            Could be/have empty / null / null entries, duplicate blocks
     *            will be ignored.
     */
    public void addBlocks(final Block... blocks) {
        if (blocks == null || blocks.length == 0) {
            return;
        }
        addBlocks(Arrays.asList(blocks));
    }

    /**
     * Add blocks as neutral past states (no moving direction). All blocks are
     * to be in the same world (no consistency checks!), the world of the first
     * block is used.
     * 
     * @param blocks
     *            Could be/have empty / null / null entries, duplicate blocks
     *            will be ignored.
     */
    public void addBlocks(final Collection<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        checkProcessBlocks(); // TODO: Remove, once sure, that processing never ever generates an exception.
        // Collect non null blocks first, set world.
        World world = null;
        for (final Block block : blocks) {
            if (block != null) {
                if (world == null) {
                    world = block.getWorld();
                }
                processBlocks.add(block);
            }
        }
        if (world == null || !processBlocks.isEmpty()) {
            processBlocks.clear(); // In case the world is null (unlikely).
            return;
        }
        // Add blocks.
        final int tick = TickTask.getTick();
        final WorldNode worldNode = getOrCreateWorldNode(world, tick);
        final long changeId = ++maxChangeId;
        // Process queued blocks.
        final BlockCache blockCache = blockCacheHandle.getHandle();
        blockCache.setAccess(world); // Assume all users always clean up after use :).
        for (final Block block : processBlocks) {
            addBlock(changeId, tick, worldNode, block.getX(), block.getY(), block.getZ(), blockCache);
        }
        blockCache.cleanup();
        processBlocks.clear();
    }

    /**
     * Neutral (no direction) adding of a block state.
     * 
     * @param changeId
     * @param tick
     * @param world
     * @param block
     * @param blockCache
     */
    private final void addBlock(final long changeId, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final BlockCache blockCache) {
        addBlockChange(changeId, tick, worldNode, x, y, z, Direction.NONE, 
                blockCache.getOrCreateBlockCacheNode(x, y, z, true));
    }

    private final WorldNode getOrCreateWorldNode(final World world, final int tick) {
        final UUID worldId = world.getUID();
        WorldNode worldNode = worldMap.get(worldId);
        if (worldNode == null) {
            // TODO: With activity tracking this should be a return.
            worldNode = new WorldNode(worldId);
            worldMap.put(worldId, worldNode);
        }
        // TODO: (else) With activity tracking still check if lastActivityTick is too old (lazily expire entire worlds).
        return worldNode;
    }

    /**
     * Add a block change.
     * 
     * @param x
     * @param y
     * @param z
     * @param direction
     *            If not NONE, moving the block into that direction is assumed.
     */
    private void addBlockChange(final long changeId, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final Direction direction, final IBlockCacheNode previousState) {
        worldNode.lastChangeTick = tick;
        final BlockChangeEntry entry = new BlockChangeEntry(changeId, tick, x, y, z, direction, previousState);
        LinkedList<BlockChangeEntry> entries = worldNode.blocks.get(x, y, z, MoveOrder.END);
        ActivityNode activityNode = worldNode.getActivityNode(x, y, z, activityResolution);
        if (entries == null) {
            entries = new LinkedList<BlockChangeTracker.BlockChangeEntry>();
            worldNode.blocks.put(x, y, z, entries, MoveOrder.END); // Add to end.
        }
        else {
            // Lazy expiration check for this block.
            if (!entries.isEmpty()) { 
                if (entries.getFirst().tick < tick - expirationAgeTicks) {
                    final int expired = expireEntries(tick - expirationAgeTicks, entries);
                    worldNode.size -= expired;
                    activityNode.count -= expired;
                }
                // Re-check in case of invalidation.
                if (!entries.isEmpty()) {
                    // Update the nextEntryTick for the last entry in the list.
                    entries.getLast().nextEntryTick = tick;
                }
            }
        }
        // TODO: Skip too fast changing states?
        entries.add(entry); // Add latest to the end always.
        activityNode.count ++;
        worldNode.size ++;
        //DebugUtil.debug("Add block change: " + x + "," + y + "," + z + " " + direction + " " + changeId); // TODO: REMOVE
    }

    private int expireEntries(final int olderThanTick, final LinkedList<BlockChangeEntry> entries) {
        int removed = 0;
        final Iterator<BlockChangeEntry> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().tick < olderThanTick) {
                it.remove();
                removed ++;
            }
            else {
                return removed;
            }
        }
        return removed;
    }

    /**
     * Check expiration on tick.
     * 
     * @param currentTick
     */
    public void checkExpiration(final int currentTick) {
        final int olderThanTick = currentTick - expirationAgeTicks;
        final Iterator<Entry<UUID, WorldNode>> it = worldMap.entrySet().iterator();
        while (it.hasNext()) {
            final WorldNode worldNode = it.next().getValue();
            if (worldNode.lastChangeTick < olderThanTick) {
                worldNode.clear();
                it.remove();
            }
            else {
                // Check for expiration of individual blocks.
                if (worldNode.size < worldNodeSkipSize) {
                    continue;
                }
                final Iterator<fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap.Entry<LinkedList<BlockChangeEntry>>> blockIt = worldNode.blocks.iterator();
                while (blockIt.hasNext()) {
                    final fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap.Entry<LinkedList<BlockChangeEntry>> entry = blockIt.next();
                    final LinkedList<BlockChangeEntry> entries = entry.getValue();
                    final ActivityNode activityNode = worldNode.getActivityNode(entry.getX(), entry.getY(), entry.getZ(), activityResolution);
                    if (!entries.isEmpty()) {
                        if (entries.getFirst().tick < olderThanTick) {
                            final int expired = expireEntries(olderThanTick, entries);
                            worldNode.size -= expired;
                            activityNode.count -= expired;
                        }
                    }
                    if (entries.isEmpty()) {
                        blockIt.remove();
                        if (activityNode.count <= 0) { // Safety first.
                            worldNode.removeActivityNode(entry.getX(), entry.getY(), entry.getZ(), activityResolution);
                        }
                    }
                }
                if (worldNode.size <= 0) {
                    // TODO: With activity tracking, nodes get removed based on last activity only.
                    it.remove();
                }
            }
        }
    }

    /**
     * Query past block states and moved blocks, including direction of moving.
     * 
     * @param ref
     *            Reference for checking the validity of BlockChangeEntry
     *            instances. No changes are made to the passed instance,
     *            canUpdateWith is called. Pass null to skip further validation.
     * @param tick
     *            The current tick. Used for lazy expiration.
     * @param worldId
     * @param x
     *            Block Coordinates.
     * @param y
     * @param z
     * @param direction
     *            Desired direction of a moved block. Pass null to ignore
     *            direction.
     * @return The matching entry, or null if there is no matching entry.
     */
    public BlockChangeEntry getBlockChangeEntry(final BlockChangeReference ref, final int tick, final UUID worldId, 
            final int x, final int y, final int z, final Direction direction) {
        final WorldNode worldNode = worldMap.get(worldId);
        if (worldNode == null) {
            return null;
        }
        return getBlockChangeEntry(ref, tick, worldNode, x, y, z, direction);
    }

    /**
     * Query past block states and moved blocks, including direction of moving.
     * 
     * @param ref
     *            Reference for checking the validity of BlockChangeEntry
     *            instances. No changes are made to the passed instance,
     *            canUpdateWith is called. Pass null to skip further validation.
     * @param tick
     *            The current tick. Used for lazy expiration.
     * @param worldNode
     * @param x
     *            Block Coordinates.
     * @param y
     * @param z
     * @param direction
     *            Desired direction of a moved block. Pass null to ignore
     *            direction.
     * @return The oldest matching entry, or null if there is no matching entry.
     */
    private BlockChangeEntry getBlockChangeEntry(final BlockChangeReference ref, final int tick, final WorldNode worldNode, 
            final int x, final int y, final int z, final Direction direction) {
        // TODO: Might add some policy (start at age, oldest first, newest first).
        final int expireOlderThanTick = tick - expirationAgeTicks;
        // Lazy expiration of entire world nodes.
        if (worldNode.lastChangeTick < expireOlderThanTick) {
            worldNode.clear();
            worldMap.remove(worldNode.worldId);
            //DebugUtil.debug("EXPIRE WORLD"); // TODO: REMOVE
            return null;
        }
        // Check individual entries.
        final LinkedList<BlockChangeEntry> entries = worldNode.blocks.get(x, y, z);
        if (entries == null) {
            //DebugUtil.debug("NO ENTRIES: " + x + "," + y + "," + z);
            return null;
        }
        final ActivityNode activityNode = worldNode.getActivityNode(x, y, z, activityResolution);
        //DebugUtil.debug("Entries at: " + x + "," + y + "," + z);
        final Iterator<BlockChangeEntry> it = entries.iterator();
        while (it.hasNext()) {
            final BlockChangeEntry entry = it.next();
            if (entry.tick < expireOlderThanTick) {
                //DebugUtil.debug("Lazy expire: " + x + "," + y + "," + z + " " + entry.id);
                it.remove();
                activityNode.count --;
            }
            else {
                if (ref == null || ref.canUpdateWith(entry) && (direction == null || entry.direction == direction)) {
                    return entry;
                }
            }
        }
        // Remove entries from map + remove world if empty.
        if (entries.isEmpty()) {
            worldNode.blocks.remove(x, y, z);
            if (worldNode.size == 0) {
                worldMap.remove(worldNode.worldId);
            }
            else if (activityNode.count <= 0) { // Safety.
                worldNode.removeActivityNode(x, y, z, activityResolution);
            }
        }
        return null;
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param pos1
     * @param pos2
     * @param margin
     * @return
     */
    public boolean hasActivity(final UUID worldId,
            final IGetPosition pos1, final IGetPosition pos2, final double margin) {
        return hasActivity(worldId, pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX(), pos2.getY(), pos2.getZ());
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param x1
     * @param y1
     * @param z1
     * @param x2
     * @param y2
     * @param z2
     * @return
     */
    public boolean hasActivity(final UUID worldId, final double x1, final double y1, final double z1,
            final double x2, final double y2, final double z2) {
        return hasActivity(worldId, Location.locToBlock(x1), Location.locToBlock(y1), Location.locToBlock(z1),
                Location.locToBlock(x2), Location.locToBlock(y2), Location.locToBlock(z2));
    }

    /**
     * Test if there has been block change activity within the specified cuboid.
     * Mind that queries for larger regions than chunk size (default 32) may be
     * inefficient. The coordinates need not be ordered.
     * 
     * @param worldId
     * @param x1
     * @param y1
     * @param z1
     * @param x2
     * @param y2
     * @param z2
     * @return
     */
    public boolean hasActivity(final UUID worldId, final int x1, final int y1, final int z1,
            final int x2, final int y2, final int z2) {
        final WorldNode worldNode = worldMap.get(worldId);
        if (worldNode == null) {
            return false;
        }
        /*
         *  TODO: After all a better data structure would allow an almost direct return (despite most of the time iterating one chunk).
         */
        final int minX = Math.min(x1, x2) / activityResolution;
        final int minY = Math.min(y1, y2) / activityResolution;
        final int minZ = Math.min(z1, z2) / activityResolution;
        final int maxX = Math.max(x1, x2) / activityResolution;
        final int maxY = Math.max(y1, y2) / activityResolution;
        final int maxZ = Math.max(z1, z2) / activityResolution;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (worldNode.activityMap.contains(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void clear() {
        for (final WorldNode worldNode : worldMap.values()) {
            worldNode.clear();
        }
        worldMap.clear();
    }

    public int size() {
        int size = 0;
        for (final WorldNode worldNode : worldMap.values()) {
            size += worldNode.size;
        }
        return size;
    }

    public int getExpirationAgeTicks() {
        return expirationAgeTicks;
    }

    public void setExpirationAgeTicks(int expirationAgeTicks) {
        this.expirationAgeTicks = expirationAgeTicks;
    }

    public int getWorldNodeSkipSize() {
        return worldNodeSkipSize;
    }

    public void setWorldNodeSkipSize(int worldNodeSkipSize) {
        this.worldNodeSkipSize = worldNodeSkipSize;
    }

    public void updateBlockCacheHandle() {
        if (this.blockCacheHandle != null) {
            this.blockCacheHandle.disableHandle();
        }
        this.blockCacheHandle = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstanceHandle(BlockCache.class);
    }

    /**
     * On starting to adding blocks: processBlocks has to be empty. If not empty, warn and clear. 
     */
    private void checkProcessBlocks() {
        if (!processBlocks.isEmpty()) {
            processBlocks.clear();
            NCPAPIProvider.getNoCheatPlusAPI().getLogManager().warning(Streams.STATUS, "BlockChangeTracker: processBlocks is not empty on starting to add blocks.");
        }
    }

}
