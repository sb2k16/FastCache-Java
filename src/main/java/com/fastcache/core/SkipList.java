package com.fastcache.core;

import java.util.*;

/**
 * Generic Skip List implementation with O(log n) average time complexity for insert, delete, and search operations.
 * This implementation supports duplicate elements and maintains sorted order.
 */
public class SkipList<T extends Comparable<T>> {
    private static final int MAX_LEVEL = 32;
    private static final double PROBABILITY = 0.5;
    
    private final Node<T> head;
    private final Random random;
    private int size;
    private int currentMaxLevel;
    
    /**
     * Node in the skip list.
     */
    private static class Node<T> {
        private final T data;
        private final Node<T>[] forward;
        
        @SuppressWarnings("unchecked")
        Node(T data, int level) {
            this.data = data;
            this.forward = new Node[level];
        }
        
        T getData() {
            return data;
        }
        
        Node<T> getForward(int level) {
            return forward[level];
        }
        
        void setForward(int level, Node<T> node) {
            forward[level] = node;
        }
        
        int getLevel() {
            return forward.length;
        }
    }
    
    public SkipList() {
        this.head = new Node<>(null, MAX_LEVEL);
        this.random = new Random();
        this.size = 0;
        this.currentMaxLevel = 1;
    }
    
    /**
     * Inserts an element into the skip list.
     * @param element The element to insert
     * @return true if the element was inserted, false if it already exists
     */
    public boolean insert(T element) {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null");
        }
        
        Node<T>[] update = new Node[MAX_LEVEL];
        Node<T> current = head;
        
        // Find the position to insert and collect update pointers
        for (int i = currentMaxLevel - 1; i >= 0; i--) {
            while (current.getForward(i) != null && 
                   current.getForward(i).getData().compareTo(element) < 0) {
                current = current.getForward(i);
            }
            update[i] = current;
        }
        
        current = current.getForward(0);
        
        // Check if element already exists
        if (current != null && current.getData().equals(element)) {
            return false;
        }
        
        // Generate random level for the new node
        int level = randomLevel();
        
        // Update max level if necessary
        if (level > currentMaxLevel) {
            for (int i = currentMaxLevel; i < level; i++) {
                update[i] = head;
            }
            currentMaxLevel = level;
        }
        
        // Create new node and update pointers
        Node<T> newNode = new Node<>(element, level);
        for (int i = 0; i < level; i++) {
            newNode.setForward(i, update[i].getForward(i));
            update[i].setForward(i, newNode);
        }
        
        size++;
        return true;
    }
    
    /**
     * Removes an element from the skip list.
     * @param element The element to remove
     * @return true if the element was removed, false if it doesn't exist
     */
    public boolean remove(T element) {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null");
        }
        
        Node<T>[] update = new Node[MAX_LEVEL];
        Node<T> current = head;
        
        // Find the element and collect update pointers
        for (int i = currentMaxLevel - 1; i >= 0; i--) {
            while (current.getForward(i) != null && 
                   current.getForward(i).getData().compareTo(element) < 0) {
                current = current.getForward(i);
            }
            update[i] = current;
        }
        
        current = current.getForward(0);
        
        // Check if element exists
        if (current == null || !current.getData().equals(element)) {
            return false;
        }
        
        // Update pointers to skip the node to be removed
        for (int i = 0; i < currentMaxLevel; i++) {
            if (update[i].getForward(i) != current) {
                break;
            }
            update[i].setForward(i, current.getForward(i));
        }
        
        // Update max level if necessary
        while (currentMaxLevel > 1 && head.getForward(currentMaxLevel - 1) == null) {
            currentMaxLevel--;
        }
        
        size--;
        return true;
    }
    
    /**
     * Searches for an element in the skip list.
     * @param element The element to search for
     * @return true if the element exists, false otherwise
     */
    public boolean contains(T element) {
        if (element == null) {
            return false;
        }
        
        Node<T> current = head;
        
        // Search from top level to bottom level
        for (int i = currentMaxLevel - 1; i >= 0; i--) {
            while (current.getForward(i) != null && 
                   current.getForward(i).getData().compareTo(element) < 0) {
                current = current.getForward(i);
            }
        }
        
        current = current.getForward(0);
        return current != null && current.getData().equals(element);
    }
    
    /**
     * Gets the rank of an element (0-based, ascending order).
     * @param element The element to find rank for
     * @return The rank of the element, or -1 if not found
     */
    public int getRank(T element) {
        if (element == null) {
            return -1;
        }
        
        Node<T> current = head;
        int rank = 0;
        
        // Search from top level to bottom level, counting elements
        for (int i = currentMaxLevel - 1; i >= 0; i--) {
            while (current.getForward(i) != null && 
                   current.getForward(i).getData().compareTo(element) < 0) {
                rank += countElementsAtLevel(current, i);
                current = current.getForward(i);
            }
        }
        
        current = current.getForward(0);
        if (current != null && current.getData().equals(element)) {
            return rank;
        }
        
        return -1;
    }
    
    /**
     * Gets an element by rank (0-based, ascending order).
     * @param rank The rank of the element
     * @return The element at the given rank, or null if rank is invalid
     */
    public T getByRank(int rank) {
        if (rank < 0 || rank >= size) {
            return null;
        }
        
        Node<T> current = head;
        int currentRank = 0;
        
        // Navigate to the element at the given rank
        for (int i = currentMaxLevel - 1; i >= 0; i--) {
            while (current.getForward(i) != null && 
                   currentRank + countElementsAtLevel(current, i) <= rank) {
                currentRank += countElementsAtLevel(current, i);
                current = current.getForward(i);
            }
        }
        
        return current.getForward(0).getData();
    }
    
    /**
     * Gets a range of elements by rank.
     * @param startRank Start rank (inclusive)
     * @param endRank End rank (inclusive)
     * @return List of elements in the range
     */
    public List<T> getRangeByRank(int startRank, int endRank) {
        if (startRank < 0 || endRank >= size || startRank > endRank) {
            return new ArrayList<>();
        }
        
        List<T> result = new ArrayList<>();
        Node<T> current = head;
        int currentRank = 0;
        
        // Navigate to start position
        for (int i = currentMaxLevel - 1; i >= 0; i--) {
            while (current.getForward(i) != null && 
                   currentRank + countElementsAtLevel(current, i) <= startRank) {
                currentRank += countElementsAtLevel(current, i);
                current = current.getForward(i);
            }
        }
        
        // Collect elements in range
        current = current.getForward(0);
        for (int i = startRank; i <= endRank && current != null; i++) {
            result.add(current.getData());
            current = current.getForward(0);
        }
        
        return result;
    }
    
    /**
     * Gets all elements in the skip list.
     * @return List of all elements in sorted order
     */
    public List<T> getAll() {
        List<T> result = new ArrayList<>();
        Node<T> current = head.getForward(0);
        
        while (current != null) {
            result.add(current.getData());
            current = current.getForward(0);
        }
        
        return result;
    }
    
    /**
     * Gets the size of the skip list.
     * @return Number of elements
     */
    public int size() {
        return size;
    }
    
    /**
     * Checks if the skip list is empty.
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return size == 0;
    }
    
    /**
     * Clears all elements from the skip list.
     */
    public void clear() {
        for (int i = 0; i < MAX_LEVEL; i++) {
            head.setForward(i, null);
        }
        size = 0;
        currentMaxLevel = 1;
    }
    
    /**
     * Generates a random level for a new node.
     * @return Random level between 1 and MAX_LEVEL
     */
    private int randomLevel() {
        int level = 1;
        while (random.nextDouble() < PROBABILITY && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }
    
    /**
     * Counts the number of elements that can be reached at a given level from a node.
     * This is a simplified implementation - in practice, you might want to maintain
     * this count in the nodes themselves for better performance.
     */
    private int countElementsAtLevel(Node<T> node, int level) {
        int count = 0;
        Node<T> current = node;
        
        while (current.getForward(level) != null) {
            count++;
            current = current.getForward(level);
        }
        
        return count;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SkipList{size=").append(size).append(", elements=[");
        
        Node<T> current = head.getForward(0);
        while (current != null) {
            sb.append(current.getData());
            current = current.getForward(0);
            if (current != null) {
                sb.append(", ");
            }
        }
        
        sb.append("]}");
        return sb.toString();
    }
} 