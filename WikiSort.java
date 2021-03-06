/***********************************************************
 WikiSort (public domain license)
 https://github.com/BonzaiThePenguin/WikiSort
 
 to run:
 javac WikiSort.java
 java WikiSort
***********************************************************/

// this version is over 4x slower on my system than the C++ version
// if you can make it faster/better, please do :)

import java.util.*;
import java.lang.*;
import java.io.*;

class Test {
	public int value;
	public int index;
}

class TestComparator implements Comparator<Test> {
    static int comparisons = 0;
	public int compare(Test a, Test b) {
    	comparisons++;
		if (a.value < b.value) return -1;
		if (a.value > b.value) return 1;
		return 0;
    }
}

// structure to represent ranges within the array
class Range {
	public int start;
	public int end;
	
	public Range(int start1, int end1) {
		start = start1;
		end = end1;
	}
	
	public Range() {
		start = 0;
		end = 0;
	}
	
	void set(int start1, int end1) {
		start = start1;
		end = end1;
	}
	
	int length() {
		return end - start;
	}
}

class Pull {
	public int from, to, count;
	public Range range;
	public Pull() { range = new Range(0, 0); }
	void reset() {
		range.set(0, 0);
		from = 0;
		to = 0;
		count = 0;
	}
}

class WikiSorter<T> {
	// use a small cache to speed up some of the operations
	// since the cache size is fixed, it's still O(1) memory!
	// just keep in mind that making it too small ruins the point (nothing will fit into it),
	// and making it too large also ruins the point (so much for "low memory"!)
	
	// also, if you change this to dynamically allocate a full-size buffer,
	// the algorithm seamlessly turns into a full-speed standard merge sort!
	private static int cache_size = 512;
	private T[] cache;
	
	public WikiSorter() {
		@SuppressWarnings("unchecked")
		T[] cache1 = (T[])new Object[cache_size];
		if (cache1 == null) cache_size = 0;
		else cache = cache1;
	}
	
	public static <T> void sort(T[] array, Comparator<T> comp) {
		new WikiSorter<T>().Sort(array, comp);
	}
	
	// toolbox functions used by the sorter
	
	// 63 -> 32, 64 -> 64, etc.
	// apparently this comes from Hacker's Delight?
	static int FloorPowerOfTwo(int value) {
		int x = value;
		x = x | (x >> 1);
		x = x | (x >> 2);
		x = x | (x >> 4);
		x = x | (x >> 8);
		x = x | (x >> 16);
		return x - (x >> 1);
	}
	
	// find the index of the first value within the range that is equal to array[index]
	int BinaryFirst(T array[], T value, Range range, Comparator<T> comp) {
		int start = range.start, end = range.end - 1;
		while (start < end) {
			int mid = start + (end - start)/2;
			if (comp.compare(array[mid], value) < 0)
				start = mid + 1;
			else
				end = mid;
		}
		if (start == range.end - 1 && comp.compare(array[start], value) < 0) start++;
		return start;
	}
    
	// find the index of the last value within the range that is equal to array[index], plus 1
	int BinaryLast(T array[], T value, Range range, Comparator<T> comp) {
		int start = range.start, end = range.end - 1;
		while (start < end) {
			int mid = start + (end - start)/2;
			if (comp.compare(value, array[mid]) >= 0)
				start = mid + 1;
			else
				end = mid;
		}
		if (start == range.end - 1 && comp.compare(value, array[start]) >= 0) start++;
		return start;
	}
	
	// n^2 sorting algorithm used to sort tiny chunks of the full array
	void InsertionSort(T array[], Range range, Comparator<T> comp) {
		for (int i = range.start + 1; i < range.end; i++) {
			T temp = array[i];
			int j;
			for (j = i; j > range.start && comp.compare(temp, array[j - 1]) < 0; j--)
				array[j] = array[j - 1];
			array[j] = temp;
		}
	}
	
	// reverse a range within the array
	void Reverse(T array[], Range range) {
		for (int index = range.length()/2 - 1; index >= 0; index--) {
			T swap = array[range.start + index];
			array[range.start + index] = array[range.end - index - 1];
			array[range.end - index - 1] = swap;
		}
	}
	
	// swap a series of values in the array
	void BlockSwap(T array[], int start1, int start2, int block_size) {
		for (int index = 0; index < block_size; index++) {
			T swap = array[start1 + index];
			array[start1 + index] = array[start2 + index];
			array[start2 + index] = swap;
		}
	}
	
	// rotate the values in an array ([0 1 2 3] becomes [1 2 3 0] if we rotate by 1)
	void Rotate(T array[], int amount, Range range, boolean use_cache) {
		if (range.length() == 0) return;
		
		int split;
		if (amount >= 0)
			split = range.start + amount;
		else
			split = range.end + amount;
		
		Range range1 = new Range(range.start, split);
		Range range2 = new Range(split, range.end);
		
		if (use_cache) {
			// if the smaller of the two ranges fits into the cache, it's *slightly* faster copying it there and shifting the elements over
			if (range1.length() <= range2.length()) {
				if (range1.length() <= cache_size) {
					java.lang.System.arraycopy(array, range1.start, cache, 0, range1.length());
					java.lang.System.arraycopy(array, range2.start, array, range1.start, range2.length());
					java.lang.System.arraycopy(cache, 0, array, range1.start + range2.length(), range1.length());
					return;
				}
			} else {
				if (range2.length() <= cache_size) {
					java.lang.System.arraycopy(array, range2.start, cache, 0, range2.length());
					java.lang.System.arraycopy(array, range1.start, array, range2.end - range1.length(), range1.length());
					java.lang.System.arraycopy(cache, 0, array, range1.start, range2.length());
					return;
				}
			}
		}
		
		Reverse(array, range1);
		Reverse(array, range2);
		Reverse(array, range);
	}
	
	// merge operation using an external buffer,
	void MergeExternal(T array[], Range A, Range B, Comparator<T> comp) {
		// A fits into the cache, so use that instead of the internal buffer
		int A_index = 0;
		int B_index = B.start;
		int insert_index = A.start;
		int A_last = A.length();
		int B_last = B.end;
		
		if (B.length() > 0 && A.length() > 0) {
			while (true) {
				if (comp.compare(array[B_index], cache[A_index]) >= 0) {
					array[insert_index] = cache[A_index];
					A_index++;
					insert_index++;
					if (A_index == A_last) break;
				} else {
					array[insert_index] = array[B_index];
					B_index++;
					insert_index++;
					if (B_index == B_last) break;
				}
			}
		}
		
		// copy the remainder of A into the final array
		java.lang.System.arraycopy(cache, A_index, array, insert_index, A_last - A_index);
	}
	
	// merge operation using an internal buffer
	void MergeInternal(T array[], Range A, Range B, Comparator<T> comp, Range buffer) {
		// whenever we find a value to add to the final array, swap it with the value that's already in that spot
		// when this algorithm is finished, 'buffer' will contain its original contents, but in a different order
		int A_count = 0, B_count = 0, insert = 0;
		
		if (B.length() > 0 && A.length() > 0) {
			while (true) {
				if (comp.compare(array[B.start + B_count], array[buffer.start + A_count]) >= 0) {
					T swap = array[A.start + insert];
					array[A.start + insert] = array[buffer.start + A_count];
					array[buffer.start + A_count] = swap;
					A_count++;
					insert++;
					if (A_count >= A.length()) break;
				} else {
					T swap = array[A.start + insert];
					array[A.start + insert] = array[B.start + B_count];
					array[B.start + B_count] = swap;
					B_count++;
					insert++;
					if (B_count >= B.length()) break;
				}
			}
		}
		
		// swap the remainder of A into the final array
		BlockSwap(array, buffer.start + A_count, A.start + insert, A.length() - A_count);
	}
	
	// merge operation without a buffer
	void MergeInPlace(T array[], Range A, Range B, Comparator<T> comp) {
		// this just repeatedly binary searches into B and rotates A into position,
		// although the paper suggests using the "rotation-based variant of the Hwang and Lin algorithm"
		A = new Range(A.start, A.end);
		B = new Range(B.start, B.end);
		
		while (A.length() > 0 && B.length() > 0) {
			// find the first place in B where the first item in A needs to be inserted
			int mid = BinaryFirst(array, array[A.start], B, comp);
			
			// rotate A into place
			int amount = mid - A.end;
			Rotate(array, -amount, new Range(A.start, mid), false);
			
			// calculate the new A and B ranges
			B.start = A.end = mid;
			A.start = BinaryLast(array, array[A.start + amount], A, comp);
		}
	}
	
	// bottom-up merge sort combined with an in-place merge algorithm for O(1) memory use
	void Sort(T array[], Comparator<T> comp) {
		int size = array.length;
		
		// if there are 32 or fewer items, just insertion sort the entire array
		if (size <= 32) {
			InsertionSort(array, new Range(0, size), comp);
			return;
		}
		
		// calculate how to scale the index value to the range within the array
		// this is essentially 64.64 fixed-point math, where we manually check for and handle overflow,
		// and where the fractional part is in base "fractional_base", rather than base 10
		int power_of_two = FloorPowerOfTwo(size);
		int fractional_base = power_of_two/16;
		int fractional_step = size % fractional_base;
		int decimal_step = size/fractional_base;
		
		// first insertion sort everything the lowest level, which is 16-31 items at a time
		int decimal = 0, fractional = 0;
		while (decimal < size) {
			int start = decimal;
			
			decimal += decimal_step;
			fractional += fractional_step;
			if (fractional >= fractional_base) {
				fractional -= fractional_base;
				decimal++;
			}
			
			int end = decimal;
			
			InsertionSort(array, new Range(start, end), comp);
		}
		
		// we need to keep track of a lot of ranges during this sort!
		Range buffer1 = new Range(), buffer2 = new Range();
		Range blockA = new Range(), blockB = new Range();
		Range lastA = new Range(), lastB = new Range();
		Range firstA = new Range();
		Range A = new Range(), B = new Range();
		
		Pull[] pull = new Pull[2];
		pull[0] = new Pull();
		pull[1] = new Pull();
		
		// then merge sort the higher levels, which can be 16-31, 32-63, 64-127, 128-255, etc.
		for (int merge_size = 16; merge_size < power_of_two; merge_size += merge_size) {
			
			// if every A and B block will fit into the cache, use a special branch specifically for merging with the cache
			// (we use < rather than <= since the block size might be one more than decimal_step)
			if (decimal_step < cache_size) {
				decimal = fractional = 0;
				while (decimal < size) {
					int start = decimal;
					
					decimal += decimal_step;
					fractional += fractional_step;
					if (fractional >= fractional_base) {
						fractional -= fractional_base;
						decimal++;
					}
					
					int mid = decimal;
					
					decimal += decimal_step;
					fractional += fractional_step;
					if (fractional >= fractional_base) {
						fractional -= fractional_base;
						decimal++;
					}
					
					int end = decimal;
					
					if (comp.compare(array[mid], array[mid - 1]) < 0) {
						// these two ranges weren't already in order, so we'll need to merge them!
						java.lang.System.arraycopy(array, start, cache, 0, mid - start);
						MergeExternal(array, new Range(start, mid), new Range(mid, end), comp);
					}
				}
			} else {
				// this is where the in-place merge logic starts!
				// 1. pull out two internal buffers each containing √A unique values
				//     1a. adjust block_size and buffer_size if we couldn't find enough unique values
				// 2. loop over the A and B areas within this level of the merge sort
				//     3. break A and B into blocks of size 'block_size'
				//     4. "tag" each of the A blocks with values from the first internal buffer
				//     5. roll the A blocks through the B blocks and drop/rotate them where they belong
				//     6. merge each A block with any B values that follow, using the cache or second the internal buffer
				// 7. sort the second internal buffer if it exists
				// 8. redistribute the two internal buffers back into the array
				
				int block_size = (int)Math.sqrt(decimal_step);
				int buffer_size = decimal_step/block_size + 1;
				
				// as an optimization, we really only need to pull out the internal buffers once for each level of merges
				// after that we can reuse the same buffers over and over, then redistribute it when we're finished with this level
				int index, last, count, pull_index = 0;
				buffer1.set(0, 0);
				buffer2.set(0, 0);
				
				pull[0].reset();
				pull[1].reset();
				
				// if every A block fits into the cache, we don't need the second internal buffer, so we can make do with only 'buffer_size' unique values
				long find = buffer_size + buffer_size;
				if (block_size <= cache_size) find = buffer_size;
				
				// we need to find either a single contiguous space containing 2√A unique values (which will be split up into two buffers of size √A each),
				// or we need to find one buffer of < 2√A unique values, and a second buffer of √A unique values,
				// OR if we couldn't find that many unique values, we need the largest possible buffer we can get
				
				// in the case where it couldn't find a single buffer of at least √A unique values,
				// all of the Merge steps must be replaced by a different merge algorithm (MergeInPlace)
				decimal = fractional = 0;
				while (decimal < size) {
					int start = decimal;
					
					decimal += decimal_step;
					fractional += fractional_step;
					if (fractional >= fractional_base) {
						fractional -= fractional_base;
						decimal++;
					}
					
					int mid = decimal;
					
					decimal += decimal_step;
					fractional += fractional_step;
					if (fractional >= fractional_base) {
						fractional -= fractional_base;
						decimal++;
					}
					
					int end = decimal;
					
					// check A (from start to mid) for the number of unique values we need to fill an internal buffer
					// these values will be pulled out to the start of A
					last = start;
					count = 1;
					for (index = start + 1; index < mid; index++) {
						if (comp.compare(array[index - 1], array[index]) < 0) {
							last = index;
							if (++count >= find) break;
						}
					}
					index = last;
					
					if (count >= buffer_size) {
						// keep track of the range within the array where we'll need to "pull out" these values to create the internal buffer
						pull[pull_index].range.set(start, end);
						pull[pull_index].count = count;
						pull[pull_index].from = index;
						pull[pull_index].to = start;
						pull_index = 1;
						
						if (count == buffer_size + buffer_size) {
							// we were able to find a single contiguous section containing 2√A unique values,
							// so this section can be used to contain both of the internal buffers we'll need
							buffer1.set(start, start + buffer_size);
							buffer2.set(start + buffer_size, start + count);
							break;
						} else if (find == buffer_size + buffer_size) {
							buffer1.set(start, start + count);
							
							// we found a buffer that contains at least √A unique values, but did not contain the full 2√A unique values,
							// so we still need to find a second separate buffer of at least √A unique values
							find = buffer_size;
						} else if (block_size <= cache_size) {
							// we found the first and only internal buffer that we need, so we're done!
							buffer1.set(start, start + count);
							break;
						} else {
							// we found a second buffer in an 'A' area containing √A unique values, so we're done!
							buffer2.set(start, start + count);
							break;
						}
					} else if (pull_index == 0 && count > buffer1.length()) {
						// keep track of the largest buffer we were able to find
						buffer1.set(start, start + count);
						
						pull[pull_index].range.set(start, end);
						pull[pull_index].count = count;
						pull[pull_index].from = index;
						pull[pull_index].to = start;
					}
					
					// check B (from mid to end) for the number of unique values we need to fill an internal buffer
					// these values will be pulled out to the end of B
					last = end - 1;
					count = 1;
					for (index = end - 2; index >= mid; index--) {
						if (comp.compare(array[index], array[index + 1]) < 0) {
							last = index;
							if (++count >= find) break;
						}
					}
					index = last;
					
					if (count >= buffer_size) {
						// keep track of the range within the array where we'll need to "pull out" these values to create the internal buffer
						pull[pull_index].range.set(start, end);
						pull[pull_index].count = count;
						pull[pull_index].from = index;
						pull[pull_index].to = end;
						pull_index = 1;
						
						if (count == buffer_size + buffer_size) {
							// we were able to find a single contiguous section containing 2√A unique values,
							// so this section can be used to contain both of the internal buffers we'll need
							buffer1.set(end - count, end - buffer_size);
							buffer2.set(end - buffer_size, end);
							break;
						} else if (find == buffer_size + buffer_size) {
							buffer1.set(end - count, end);
							
							// we found a buffer that contains at least √A unique values, but did not contain the full 2√A unique values,
							// so we still need to find a second separate buffer of at least √A unique values
							find = buffer_size;
						} else if (block_size <= cache_size) {
							// we found the first and only internal buffer that we need, so we're done!
							buffer1.set(end - count, end);
							break;
						} else {
							// we found a second buffer in an 'B' area containing √A unique values, so we're done!
							buffer2.set(end - count, end);
							
							// buffer2 will be pulled out from a 'B' area, so if the first buffer was pulled out from the corresponding 'A' area,
							// we need to adjust the end point for that A area so it knows to stop redistributing its values before reaching buffer2
							if (pull[0].range.start == start) pull[0].range.end -= pull[1].count;
							
							break;
						}
					} else if (pull_index == 0 && count > buffer1.length()) {
						// keep track of the largest buffer we were able to find
						buffer1.set(end - count, end);
						
						pull[pull_index].range.set(start, end);
						pull[pull_index].count = count;
						pull[pull_index].from = index;
						pull[pull_index].to = end;
					}
				}
				
				// pull out the two ranges so we can use them as internal buffers
				for (pull_index = 0; pull_index < 2; pull_index++) {
					int length = pull[pull_index].count;
					count = 0;
					
					if (pull[pull_index].to < pull[pull_index].from) {
						// we're pulling the values out to the left, which means the start of an A area
						for (index = pull[pull_index].from; count < length; index--) {
							if (index == pull[pull_index].to || comp.compare(array[index - 1], array[index]) < 0) {
								Rotate(array, -count, new Range(index + 1, pull[pull_index].from + 1), true);
								pull[pull_index].from = index + count;
								count++;
							}
						}
					} else if (pull[pull_index].to > pull[pull_index].from) {
						// we're pulling values out to the right, which means the end of a B area
						for (index = pull[pull_index].from; count < length; index++) {
							if (index == pull[pull_index].to - 1 || comp.compare(array[index], array[index + 1]) < 0) {
								Rotate(array, count, new Range(pull[pull_index].from, index), true);
								pull[pull_index].from = index - count;
								count++;
							}
						}
					}
				}
				
				// adjust block_size and buffer_size based on the values we were able to pull out
				buffer_size = buffer1.length();
				block_size = decimal_step/buffer_size + 1;
				
				// the first buffer NEEDS to be large enough to tag each of the evenly sized A blocks,
				// so this was originally here to test the math for adjusting block_size above
				//if ((decimal_step + 1)/block_size > buffer_size) throw new RuntimeException();
				
				// now that the two internal buffers have been created, it's time to merge each A+B combination at this level of the merge sort!
				decimal = fractional = 0;
				while (decimal < size) {
					int start = decimal;
					
					decimal += decimal_step;
					fractional += fractional_step;
					if (fractional >= fractional_base) {
						fractional -= fractional_base;
						decimal++;
					}
					
					int mid = decimal;
					
					decimal += decimal_step;
					fractional += fractional_step;
					if (fractional >= fractional_base) {
						fractional -= fractional_base;
						decimal++;
					}
					
					int end = decimal;
					
					// calculate the ranges for A and B, and make sure to remove any portions that are being used by the internal buffers
					A.set(start, mid);
					B.set(mid, end);
					
					for (pull_index = 0; pull_index < 2; pull_index++) {
						if (start == pull[pull_index].range.start) {
							if (pull[pull_index].from > pull[pull_index].to)
								A.start += pull[pull_index].count;
							else if (pull[pull_index].from < pull[pull_index].to)
								B.end -= pull[pull_index].count;
						}
					}
					
					if (comp.compare(array[B.end - 1], array[A.start]) < 0) {
						// the two ranges are in reverse order, so a simple rotation should fix it
						Rotate(array, A.end - A.start, new Range(A.start, B.end), true);
					} else if (comp.compare(array[A.end], array[A.end - 1]) < 0) {
						// these two ranges weren't already in order, so we'll need to merge them!
						
						// break the remainder of A into blocks. firstA is the uneven-sized first A block
						blockA.set(A.start, A.end);
						firstA.set(A.start, A.start + blockA.length() % block_size);
						
						// swap the second value of each A block with the value in buffer1
						index = 0;
						for (int indexA = firstA.end + 1; indexA < blockA.end; indexA += block_size)  {
							T swap = array[buffer1.start + index];
							array[buffer1.start + index] = array[indexA];
							array[indexA] = swap;
							index++;
						}
						
						// start rolling the A blocks through the B blocks!
						// whenever we leave an A block behind, we'll need to merge the previous A block with any B blocks that follow it, so track that information as well
						lastA.set(firstA.start, firstA.end);
						lastB.set(0, 0);
						blockB.set(B.start, B.start + Math.min(block_size, B.length()));
						blockA.start += firstA.length();
						
						int minA = blockA.start;
						int indexA = 0;
						T min_value = array[minA];
						
						// if the first unevenly sized A block fits into the cache, copy it there for when we go to Merge it
						// otherwise, if the second buffer is available, block swap the contents into that
						if (lastA.length() <= cache_size)
							java.lang.System.arraycopy(array, lastA.start, cache, 0, lastA.length());
						else if (buffer2.length() > 0)
							BlockSwap(array, lastA.start, buffer2.start, lastA.length());
						
						while (true) {
							// if there's a previous B block and the first value of the minimum A block is <= the last value of the previous B block,
							// then drop that minimum A block behind. or if there are no B blocks left then keep dropping the remaining A blocks.
							if ((lastB.length() > 0 && comp.compare(array[lastB.end - 1], min_value) >= 0) || blockB.length() == 0) {
								// figure out where to split the previous B block, and rotate it at the split
								int B_split = BinaryFirst(array, min_value, lastB, comp);
								int B_remaining = lastB.end - B_split;
								
								// swap the minimum A block to the beginning of the rolling A blocks
								BlockSwap(array, blockA.start, minA, block_size);
								
								// we need to swap the second item of the previous A block back with its original value, which is stored in buffer1
								// since the firstA block did not have its value swapped out, we need to make sure the previous A block is not unevenly sized
								T swap = array[blockA.start + 1];
								array[blockA.start + 1] = array[buffer1.start + indexA];
								array[buffer1.start + indexA] = swap;
								indexA++;
								
								// locally merge the previous A block with the B values that follow it, using the buffer as swap space
								if (lastA.length() <= cache_size)
									MergeExternal(array, lastA, new Range(lastA.end, B_split), comp);
								else if (buffer2.length() > 0)
									MergeInternal(array, lastA, new Range(lastA.end, B_split), comp, buffer2);
								else
									MergeInPlace(array, lastA, new Range(lastA.end, B_split), comp);
								
								if (buffer2.length() > 0 || block_size <= cache_size) {
									// copy the previous A block into the cache or buffer2, since that's where we need it to be when we go to merge it anyway
									if (block_size <= cache_size)
										java.lang.System.arraycopy(array, blockA.start, cache, 0, block_size);
									else
										BlockSwap(array, blockA.start, buffer2.start, block_size);
									
									// this is equivalent to rotating, but faster
									// the area normally taken up by the A block is either the contents of buffer2, or data we don't need anymore since we memcopied it
									// either way, we don't need to retain the order of those items, so instead of rotating we can just block swap B to where it belongs
									BlockSwap(array, B_split, blockA.start + block_size - B_remaining, B_remaining);
								} else {
									// we are unable to use the 'buffer2' trick to speed up the rotation operation since buffer2 doesn't exist, so perform a normal rotation
									Rotate(array, blockA.start - B_split, new Range(B_split, blockA.start + block_size), true);
								}
								
								// now we need to update the ranges and stuff
								lastA.set(blockA.start - B_remaining, blockA.start - B_remaining + block_size);
								lastB.set(lastA.end, lastA.end + B_remaining);
								
								blockA.start += block_size;
								if (blockA.length() == 0)
									break;
								
								// search the second value of the remaining A blocks to find the new minimum A block (that's why we wrote unique values to them!)
								minA = blockA.start + 1;
								for (int findA = minA + block_size; findA < blockA.end; findA += block_size)
									if (comp.compare(array[findA], array[minA]) < 0)
										minA = findA;
								minA = minA - 1; // decrement once to get back to the start of that A block
								min_value = array[minA];
								
							} else if (blockB.length() < block_size) {
								// move the last B block, which is unevenly sized, to before the remaining A blocks, by using a rotation
								// the cache is disabled here since it might contain the contents of the previous A block
								Rotate(array, -blockB.length(), new Range(blockA.start, blockB.end), false);
								
								lastB.set(blockA.start, blockA.start + blockB.length());
								blockA.start += blockB.length();
								blockA.end += blockB.length();
								minA += blockB.length();
								blockB.end = blockB.start;
							} else {
								// roll the leftmost A block to the end by swapping it with the next B block
								BlockSwap(array, blockA.start, blockB.start, block_size);
								lastB.set(blockA.start, blockA.start + block_size);
								if (minA == blockA.start)
									minA = blockA.end;
								
								blockA.start += block_size;
								blockA.end += block_size;
								blockB.start += block_size;
								blockB.end += block_size;
								
								if (blockB.end > B.end)
									blockB.end = B.end;
							}
						}
						
						// merge the last A block with the remaining B values
						if (lastA.length() <= cache_size)
							MergeExternal(array, lastA, new Range(lastA.end, B.end), comp);
						else if (buffer2.length() > 0)
							MergeInternal(array, lastA, new Range(lastA.end, B.end), comp, buffer2);
						else
							MergeInPlace(array, lastA, new Range(lastA.end, B.end), comp);
					}
				}
				
				// when we're finished with this merge step we should have the one or two internal buffers left over, where the second buffer is all jumbled up
				// insertion sort the second buffer, then redistribute the buffers back into the array using the opposite process used for creating the buffer
				
				// while an unstable sort like quick sort could be applied here, in benchmarks it was consistently slightly slower than a simple insertion sort,
				// even for tens of millions of items. this may be because insertion sort is quite fast when the data is already somewhat sorted, like it is here
				InsertionSort(array, buffer2, comp);
				
				for (pull_index = 0; pull_index < 2; pull_index++) {
					if (pull[pull_index].from > pull[pull_index].to) {
						// the values were pulled out to the left, so redistribute them back to the right
						Range buffer = new Range(pull[pull_index].range.start, pull[pull_index].range.start + pull[pull_index].count);
						for (index = buffer.end; buffer.length() > 0; index++) {
							if (index == pull[pull_index].range.end || comp.compare(array[index], array[buffer.start]) >= 0) {
								int amount = index - buffer.end;
								Rotate(array, -amount, new Range(buffer.start, index), true);
								buffer.start += (amount + 1);
								buffer.end += amount;
								index--;
							}
						}
					} else if (pull[pull_index].from < pull[pull_index].to) {
						// the values were pulled out to the right, so redistribute them back to the left
						Range buffer = new Range(pull[pull_index].range.end - pull[pull_index].count, pull[pull_index].range.end);
						for (index = buffer.start; buffer.length() > 0; index--) {
							if (index == pull[pull_index].range.start || comp.compare(array[buffer.end - 1], array[index - 1]) >= 0) {
								int amount = buffer.start - index;
								Rotate(array, amount, new Range(index, buffer.end), true);
								buffer.start -= amount;
								buffer.end -= (amount + 1);
								index++;
							}
						}
					}
				}
			}
			
			// double the size of each A and B area that will be merged in the next level
			decimal_step += decimal_step;
			fractional_step += fractional_step;
			if (fractional_step >= fractional_base) {
				fractional_step -= fractional_base;
				decimal_step++;
			}
		}
	}
}

class MergeSorter<T> {
	// n^2 sorting algorithm used to sort tiny chunks of the full array
	void InsertionSort(T array[], Range range, Comparator<T> comp) {
		for (int i = range.start + 1; i < range.end; i++) {
			T temp = array[i];
			int j;
			for (j = i; j > range.start && comp.compare(temp, array[j - 1]) < 0; j--)
				array[j] = array[j - 1];
			array[j] = temp;
		}
	}
	
	// standard merge sort, so we have a baseline for how well the in-place merge works
	void SortR(T array[], Range range, Comparator<T> comp, T buffer[]) {
		if (range.length() < 32) {
			// insertion sort
			InsertionSort(array, range, comp);
			return;
		}
		
		int mid = range.start + (range.end - range.start)/2;
		Range A = new Range(range.start, mid);
		Range B = new Range(mid, range.end);
		
		SortR(array, A, comp, buffer);
		SortR(array, B, comp, buffer);
		
		// standard merge operation here (only A is copied to the buffer)
		java.lang.System.arraycopy(array, A.start, buffer, 0, A.length());
		int A_count = 0, B_count = 0, insert = 0;
		while (A_count < A.length() && B_count < B.length()) {
			if (comp.compare(array[A.end + B_count], buffer[A_count]) >= 0) {
				array[A.start + insert] = buffer[A_count];
				A_count++;
			} else {
				array[A.start + insert] = array[A.end + B_count];
				B_count++;
			}
			insert++;
		}
		
		java.lang.System.arraycopy(buffer, A_count, array, A.start + insert, A.length() - A_count);
	}
	
	void Sort(T array[], Comparator<T> comp) {
		@SuppressWarnings("unchecked")
		T[] buffer = (T[]) new Object[array.length];
		SortR(array, new Range(0, array.length), comp, buffer);
	}
	
	public static <T> void sort(T[] array, Comparator<T> comp) {
		new MergeSorter<T>().Sort(array, comp);
	}
}

class SortRandom {
	public static Random rand;
	public static int nextInt(int max) {
		// set the seed on the random number generator
		if (rand == null) rand = new Random();
		return rand.nextInt(max);
	}
	public static int nextInt() {
		return nextInt(2147483647);
	}
}

class Testing {
	int value(int index, int total) {
		return index;
	}
}

class TestingPathological extends Testing {
	int value(int index, int total) {
		if (index == 0) return 10;
		else if (index < total/2) return 11;
		else if (index == total - 1) return 10;
		return 9;
	}
}

class TestingRandom extends Testing {
	int value(int index, int total) {
		return SortRandom.nextInt();
	}
}

// random distribution of few values was a problem with the last version, but it's better now
// although the algorithm in the Merge function still isn't the one the paper suggests using!
class TestingRandomFew extends Testing {
	int value(int index, int total) {
		return SortRandom.nextInt(100);
	}
}

class TestingMostlyDescending extends Testing {
	int value(int index, int total) {
		return total - index + SortRandom.nextInt(5) - 2;
	}
}

class TestingMostlyAscending extends Testing {
	int value(int index, int total) {
		return index + SortRandom.nextInt(5) - 2;
	}
}

class TestingAscending extends Testing {
	int value(int index, int total) {
		return index;
	}
}

class TestingDescending extends Testing {
	int value(int index, int total) {
		return total - index;
	}
}

class TestingEqual extends Testing {
	int value(int index, int total) {
		return 1000;
	}
}

class TestingJittered extends Testing {
	int value(int index, int total) {
		return (SortRandom.nextInt(100) <= 90) ? index : (index - 2);
	}
}

class TestingMostlyEqual extends Testing {
	int value(int index, int total) {
		return 1000 + SortRandom.nextInt(4);
	}
}

class WikiSort {
	static double Seconds() {
		return System.currentTimeMillis()/1000.0;
	}
	
	static void Verify(Test array[], Range range, TestComparator comp, String msg) {
		for (int index = range.start + 1; index < range.end; index++) {
			// if it's in ascending order then we're good
			// if both values are equal, we need to make sure the index values are ascending
			if (!(comp.compare(array[index - 1], array[index]) < 0 ||
				  (comp.compare(array[index], array[index - 1]) == 0 && array[index].index > array[index - 1].index))) {
				
				//for (int index2 = range.start; index2 < range.end; index2++)
				//	System.out.println(array[index2].value + " (" + array[index2].index + ")");
				
				System.out.println("failed with message: " + msg);
				throw new RuntimeException();
			}
		}
	}
	
	public static void main (String[] args) throws java.lang.Exception {
		int max_size = 1500000;
		TestComparator comp = new TestComparator();
		Test[] array1;
		Test[] array2;
		int compares1, compares2, total_compares1 = 0, total_compares2 = 0;
		
		Testing[] test_cases = {
			new TestingPathological(),
			new TestingRandom(),
			new TestingRandomFew(),
			new TestingMostlyDescending(),
			new TestingMostlyAscending(),
			new TestingAscending(),
			new TestingDescending(),
			new TestingEqual(),
			new TestingJittered(),
			new TestingMostlyEqual()
		};
		
		WikiSorter<Test> Wiki = new WikiSorter<Test>();
		MergeSorter<Test> Merge = new MergeSorter<Test>();
		
		System.out.println("running test cases...");
		int total = max_size;
		array1 = new Test[total];
		array2 = new Test[total];
		
		for (int test_case = 0; test_case < test_cases.length; test_case++) {
			
			for (int index = 0; index < total; index++) {
				Test item = new Test();
				
				item.value = test_cases[test_case].value(index, total);
				item.index = index;
				
				array1[index] = item;
				array2[index] = item;
			}
			
			Wiki.Sort(array1, comp);
			Merge.Sort(array2, comp);
			
			Verify(array1, new Range(0, total), comp, "test case failed");
			for (int index = 0; index < total; index++) {
				if (comp.compare(array1[index], array2[index]) != 0) throw new Exception();
				if (array2[index].index != array1[index].index) throw new Exception();
			}
		}
		System.out.println("passed!");
		
		double total_time = Seconds();
		double total_time1 = 0, total_time2 = 0;
		
		for (total = 0; total < max_size; total += 2048 * 16) {
			array1 = new Test[total];
			array2 = new Test[total];
			
			for (int index = 0; index < total; index++) {
				Test item = new Test();
				
				item.value = SortRandom.nextInt();
				item.index = index;
				
				array1[index] = item;
				array2[index] = item;
			}
			
			double time1 = Seconds();
			TestComparator.comparisons = 0;
			Wiki.Sort(array1, comp);
			time1 = Seconds() - time1;
			total_time1 += time1;
			compares1 = TestComparator.comparisons;
			total_compares1 += compares1;
			
			double time2 = Seconds();
			TestComparator.comparisons = 0;
			Merge.Sort(array2, comp);
			time2 = Seconds() - time2;
			total_time2 += time2;
			compares2 = TestComparator.comparisons;
			total_compares2 += compares2;
			
			System.out.println("[" + total + "] WikiSort: " + time1 + " seconds, MergeSort: " + time2 + " seconds (" + time2/time1 * 100 + "%)");
			System.out.println("[" + total + "] WikiSort: " + compares1 + " compares, MergeSort: " + compares2 + " compares (" + compares1 * 100.0/compares2 + "%)");
			
			// make sure the arrays are sorted correctly, and that the results were stable
			System.out.println("verifying...");
			
			Verify(array1, new Range(0, total), comp, "testing the final array");
			for (int index = 0; index < total; index++) {
				if (comp.compare(array1[index], array2[index]) != 0) throw new Exception();
				if (array2[index].index != array1[index].index) throw new Exception();
			}
			
			System.out.println("correct!");
		}
		
		total_time = Seconds() - total_time;
		System.out.println("tests completed in " + total_time + " seconds");
		System.out.println("WikiSort: " + total_time1 + " seconds, MergeSort: " + total_time2 + " seconds (" + total_time2/total_time1 * 100 + "%)");
		System.out.println("WikiSort: " + total_compares1 + " compares, MergeSort: " + total_compares2 + " compares (" + total_compares1 * 100.0/total_compares2 + "%)");
	}
}
