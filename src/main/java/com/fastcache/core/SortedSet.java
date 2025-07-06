package com.fastcache.core;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe sorted set implementation with O(log n) average time complexity for most operations.
 * Uses a skip list for score-based ordering and a hash map for O(1) member lookups.
 */
public class SortedSet {
    private final SkipList<SortedSetEntry> scoreIndex;
    private final Map<String, Double> memberToScore;
    private final ReadWriteLock lock;
    
    public SortedSet() {
        this.scoreIndex = new SkipList<>();
        this.memberToScore = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }
    
    /**
     * Adds a member with the specified score to the sorted set.
     * If the member already exists, the score is updated.
     * 
     * @param member The member to add
     * @param score The score for the member
     * @return true if a new member was added, false if an existing member was updated
     */
    public boolean add(String member, double score) {
        if (member == null) {
            throw new IllegalArgumentException("Member cannot be null");
        }
        
        lock.writeLock().lock();
        try {
            Double existingScore = memberToScore.get(member);
            
            if (existingScore != null) {
                // Member exists, update score
                if (Double.compare(existingScore, score) == 0) {
                    return false; // No change
                }
                
                // Remove old entry from skip list
                scoreIndex.remove(new SortedSetEntry(member, existingScore));
            }
            
            // Add new entry
            SortedSetEntry entry = new SortedSetEntry(member, score);
            scoreIndex.insert(entry);
            memberToScore.put(member, score);
            
            return existingScore == null;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Adds multiple members with their scores to the sorted set.
     * 
     * @param memberScores Map of member to score
     * @return Number of new members added
     */
    public int add(Map<String, Double> memberScores) {
        if (memberScores == null || memberScores.isEmpty()) {
            return 0;
        }
        
        lock.writeLock().lock();
        try {
            int added = 0;
            for (Map.Entry<String, Double> entry : memberScores.entrySet()) {
                if (add(entry.getKey(), entry.getValue())) {
                    added++;
                }
            }
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Removes a member from the sorted set.
     * 
     * @param member The member to remove
     * @return true if the member was removed, false if it didn't exist
     */
    public boolean remove(String member) {
        if (member == null) {
            return false;
        }
        
        lock.writeLock().lock();
        try {
            Double score = memberToScore.remove(member);
            if (score != null) {
                scoreIndex.remove(new SortedSetEntry(member, score));
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Removes multiple members from the sorted set.
     * 
     * @param members The members to remove
     * @return Number of members removed
     */
    public int remove(Collection<String> members) {
        if (members == null || members.isEmpty()) {
            return 0;
        }
        
        lock.writeLock().lock();
        try {
            int removed = 0;
            for (String member : members) {
                if (remove(member)) {
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the score of a member.
     * 
     * @param member The member
     * @return The score, or null if the member doesn't exist
     */
    public Double getScore(String member) {
        if (member == null) {
            return null;
        }
        
        lock.readLock().lock();
        try {
            return memberToScore.get(member);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the rank of a member (0-based, ascending order).
     * 
     * @param member The member
     * @return The rank, or -1 if the member doesn't exist
     */
    public int getRank(String member) {
        if (member == null) {
            return -1;
        }
        
        lock.readLock().lock();
        try {
            Double score = memberToScore.get(member);
            if (score == null) {
                return -1;
            }
            return scoreIndex.getRank(new SortedSetEntry(member, score));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the reverse rank of a member (0-based, descending order).
     * 
     * @param member The member
     * @return The reverse rank, or -1 if the member doesn't exist
     */
    public int getReverseRank(String member) {
        int rank = getRank(member);
        if (rank == -1) {
            return -1;
        }
        return size() - 1 - rank;
    }
    
    /**
     * Gets a member by rank (0-based, ascending order).
     * 
     * @param rank The rank
     * @return The member, or null if rank is invalid
     */
    public String getByRank(int rank) {
        lock.readLock().lock();
        try {
            SortedSetEntry entry = scoreIndex.getByRank(rank);
            return entry != null ? entry.getMember() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets a member by reverse rank (0-based, descending order).
     * 
     * @param reverseRank The reverse rank
     * @return The member, or null if reverse rank is invalid
     */
    public String getByReverseRank(int reverseRank) {
        if (reverseRank < 0) {
            return null;
        }
        return getByRank(size() - 1 - reverseRank);
    }
    
    /**
     * Gets a range of members by rank.
     * 
     * @param startRank Start rank (inclusive)
     * @param endRank End rank (inclusive)
     * @return List of members in the range
     */
    public List<String> getRangeByRank(int startRank, int endRank) {
        lock.readLock().lock();
        try {
            List<SortedSetEntry> entries = scoreIndex.getRangeByRank(startRank, endRank);
            List<String> members = new ArrayList<>(entries.size());
            for (SortedSetEntry entry : entries) {
                members.add(entry.getMember());
            }
            return members;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets a range of members by reverse rank.
     * 
     * @param startReverseRank Start reverse rank (inclusive)
     * @param endReverseRank End reverse rank (inclusive)
     * @return List of members in the range
     */
    public List<String> getRangeByReverseRank(int startReverseRank, int endReverseRank) {
        int size = size();
        int startRank = size - 1 - endReverseRank;
        int endRank = size - 1 - startReverseRank;
        return getRangeByRank(startRank, endRank);
    }
    
    /**
     * Gets a range of members with scores by rank.
     * 
     * @param startRank Start rank (inclusive)
     * @param endRank End rank (inclusive)
     * @return Map of member to score in the range
     */
    public Map<String, Double> getRangeWithScoresByRank(int startRank, int endRank) {
        lock.readLock().lock();
        try {
            List<SortedSetEntry> entries = scoreIndex.getRangeByRank(startRank, endRank);
            Map<String, Double> result = new LinkedHashMap<>(entries.size());
            for (SortedSetEntry entry : entries) {
                result.put(entry.getMember(), entry.getScore());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets a range of members with scores by reverse rank.
     * 
     * @param startReverseRank Start reverse rank (inclusive)
     * @param endReverseRank End reverse rank (inclusive)
     * @return Map of member to score in the range
     */
    public Map<String, Double> getRangeWithScoresByReverseRank(int startReverseRank, int endReverseRank) {
        int size = size();
        int startRank = size - 1 - endReverseRank;
        int endRank = size - 1 - startReverseRank;
        return getRangeWithScoresByRank(startRank, endRank);
    }
    
    /**
     * Gets a range of members by score.
     * 
     * @param minScore Minimum score (inclusive)
     * @param maxScore Maximum score (inclusive)
     * @return List of members in the score range
     */
    public List<String> getRangeByScore(double minScore, double maxScore) {
        lock.readLock().lock();
        try {
            List<String> members = new ArrayList<>();
            for (SortedSetEntry entry : scoreIndex.getAll()) {
                if (entry.getScore() >= minScore && entry.getScore() <= maxScore) {
                    members.add(entry.getMember());
                }
            }
            return members;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets a range of members with scores by score.
     * 
     * @param minScore Minimum score (inclusive)
     * @param maxScore Maximum score (inclusive)
     * @return Map of member to score in the score range
     */
    public Map<String, Double> getRangeWithScoresByScore(double minScore, double maxScore) {
        lock.readLock().lock();
        try {
            Map<String, Double> result = new LinkedHashMap<>();
            for (SortedSetEntry entry : scoreIndex.getAll()) {
                if (entry.getScore() >= minScore && entry.getScore() <= maxScore) {
                    result.put(entry.getMember(), entry.getScore());
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Increments the score of a member by the specified amount.
     * If the member doesn't exist, it is added with the increment as the score.
     * 
     * @param member The member
     * @param increment The amount to increment by
     * @return The new score
     */
    public double incrementScore(String member, double increment) {
        if (member == null) {
            throw new IllegalArgumentException("Member cannot be null");
        }
        
        lock.writeLock().lock();
        try {
            Double currentScore = memberToScore.get(member);
            double newScore = (currentScore != null ? currentScore : 0.0) + increment;
            
            add(member, newScore);
            return newScore;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the size of the sorted set.
     * 
     * @return Number of members
     */
    public int size() {
        lock.readLock().lock();
        try {
            return memberToScore.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Checks if the sorted set is empty.
     * 
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return size() == 0;
    }
    
    /**
     * Checks if a member exists in the sorted set.
     * 
     * @param member The member to check
     * @return true if the member exists, false otherwise
     */
    public boolean contains(String member) {
        if (member == null) {
            return false;
        }
        
        lock.readLock().lock();
        try {
            return memberToScore.containsKey(member);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets all members in the sorted set.
     * 
     * @return List of all members in score order
     */
    public List<String> getAllMembers() {
        lock.readLock().lock();
        try {
            List<String> members = new ArrayList<>();
            for (SortedSetEntry entry : scoreIndex.getAll()) {
                members.add(entry.getMember());
            }
            return members;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets all members with their scores.
     * 
     * @return Map of all members to their scores
     */
    public Map<String, Double> getAllWithScores() {
        lock.readLock().lock();
        try {
            return new HashMap<>(memberToScore);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Clears all members from the sorted set.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            scoreIndex.clear();
            memberToScore.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the minimum score in the sorted set.
     * 
     * @return The minimum score, or null if the set is empty
     */
    public Double getMinScore() {
        lock.readLock().lock();
        try {
            SortedSetEntry first = scoreIndex.getByRank(0);
            return first != null ? first.getScore() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the maximum score in the sorted set.
     * 
     * @return The maximum score, or null if the set is empty
     */
    public Double getMaxScore() {
        lock.readLock().lock();
        try {
            SortedSetEntry last = scoreIndex.getByRank(size() - 1);
            return last != null ? last.getScore() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the member with the minimum score.
     * 
     * @return The member with minimum score, or null if the set is empty
     */
    public String getMinMember() {
        lock.readLock().lock();
        try {
            SortedSetEntry first = scoreIndex.getByRank(0);
            return first != null ? first.getMember() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the member with the maximum score.
     * 
     * @return The member with maximum score, or null if the set is empty
     */
    public String getMaxMember() {
        lock.readLock().lock();
        try {
            SortedSetEntry last = scoreIndex.getByRank(size() - 1);
            return last != null ? last.getMember() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return "SortedSet{size=" + size() + ", members=" + memberToScore + "}";
        } finally {
            lock.readLock().unlock();
        }
    }
} 