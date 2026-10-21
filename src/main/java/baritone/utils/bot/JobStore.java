/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.bot;

import java.nio.file.Path;
import java.util.*;

/** Saved requests always reload paused. Inventory contents and paths are never fabricated on restart. */
public final class JobStore {
    public record Job(long id, int quantity, List<String> blocks, int deposited, UUID owner) {
        public Job {
            if (id < 1 || quantity < 1 || quantity > 1000000 || blocks == null || blocks.isEmpty() || blocks.size() > 32
                    || blocks.stream().anyMatch(s -> !s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) || deposited < 0)
                throw new IllegalArgumentException("Invalid mining job");
            blocks = List.copyOf(blocks);
        }
        public Job withDeposited(int amount) { return new Job(id, quantity, blocks, Math.max(0, amount), owner); }
    }
    private final Path file;
    public JobStore(Path root) { file = root.resolve("bot-jobs.properties"); }
    public List<Job> read() {
        Properties p = AtomicProperties.read(file);
        int size = Integer.parseInt(p.getProperty("count", "0"));
        if (size < 0 || size > 64) throw new IllegalStateException("Invalid saved job count");
        List<Job> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < size; i++) {
            String prefix = "job." + i + ".";
            String owner = p.getProperty(prefix + "owner", "");
            Job job = new Job(Long.parseLong(p.getProperty(prefix + "id")), Integer.parseInt(p.getProperty(prefix + "quantity")),
                    List.of(p.getProperty(prefix + "blocks").split(",")), Integer.parseInt(p.getProperty(prefix + "deposited", "0")),
                    owner.isBlank() ? null : UUID.fromString(owner));
            if (!ids.add(job.id())) throw new IllegalStateException("Duplicate saved job ID");
            result.add(job);
        }
        return result;
    }
    public void write(List<Job> jobs) {
        if (jobs.size() > 64) throw new IllegalStateException("Job queue is full (64)");
        Properties p = new Properties(); p.setProperty("count", Integer.toString(jobs.size()));
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i); String prefix = "job." + i + ".";
            p.setProperty(prefix + "id", Long.toString(j.id())); p.setProperty(prefix + "quantity", Integer.toString(j.quantity()));
            p.setProperty(prefix + "blocks", String.join(",", j.blocks())); p.setProperty(prefix + "deposited", Integer.toString(j.deposited()));
            if (j.owner() != null) p.setProperty(prefix + "owner", j.owner().toString());
        }
        AtomicProperties.write(file, p);
    }
}
