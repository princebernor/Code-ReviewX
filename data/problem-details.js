/* Full problem statements written for this demo for a small set of well-known sheet problems.
 * The coding sheet itself only lists titles and practice links, so every other problem
 * shows its title, the sheet's tip and its source link instead of an invented statement.
 * Statements below describe the standard version of each problem; the linked source may differ slightly. */
window.CRX_DETAILS = {
  1: {
    desc: "Given an integer n, print \"Even\" if it is divisible by 2, otherwise print \"Odd\".",
    input: "A single integer n.", output: "The word Even or Odd.",
    examples: [{ in: "n = 4", out: "Even" }, { in: "n = -7", out: "Odd", note: "Negative numbers follow the same rule." }],
    constraints: ["-10⁹ ≤ n ≤ 10⁹"],
    tests: [{ in: "4", out: "Even" }, { in: "7", out: "Odd" }, { in: "0", out: "Even" }, { in: "-7", out: "Odd" }],
    approach: "n % 2 is 0 for even numbers. Be careful: in C, C++, Java and JavaScript, -7 % 2 is -1, not 1, so test for equality with 0 instead of equality with 1. A bitwise alternative is (n & 1), which also works for negatives in two's complement.",
    time: "O(1)", space: "O(1)",
    solution: { python: "def even_or_odd(n):\n    return \"Even\" if n % 2 == 0 else \"Odd\"", cpp: "string evenOrOdd(long long n) {\n    return (n % 2 == 0) ? \"Even\" : \"Odd\";\n}" }
  },
  26: {
    desc: "Given an array of integers arr and an integer target, determine whether there exist two distinct indices i and j such that arr[i] + arr[j] == target.",
    input: "An integer array arr and an integer target.", output: "true if such a pair exists, otherwise false.",
    examples: [{ in: "arr = [1, 4, 45, 6, 10, 8], target = 16", out: "true", note: "6 + 10 = 16" }, { in: "arr = [1, 2, 4, 3, 6], target = 11", out: "false" }],
    constraints: ["2 ≤ arr.length ≤ 10⁵", "-10⁵ ≤ arr[i] ≤ 10⁵"],
    tests: [{ in: "[1,4,45,6,10,8], 16", out: "true" }, { in: "[1,2,4,3,6], 11", out: "false" }, { in: "[5,5], 10", out: "true" }, { in: "[5], 10", out: "false" }],
    approach: "Brute force checks every pair in O(n²). Better: walk the array once and keep a set of values seen so far. For each x, if target − x is already in the set, a pair exists. Alternatively, sort and move two pointers inward: O(n log n) time, O(1) extra space.",
    time: "O(n) with a hash set", space: "O(n)",
    solution: { python: "def has_pair(arr, target):\n    seen = set()\n    for x in arr:\n        if target - x in seen:\n            return True\n        seen.add(x)\n    return False", java: "boolean hasPair(int[] arr, int target) {\n    Set<Integer> seen = new HashSet<>();\n    for (int x : arr) {\n        if (seen.contains(target - x)) return true;\n        seen.add(x);\n    }\n    return false;\n}" }
  },
  36: {
    desc: "Given n non-negative integers representing an elevation map where each bar has width 1, compute how much water it can trap after raining.",
    input: "An integer array height.", output: "Total units of trapped water.",
    examples: [{ in: "height = [0,1,0,2,1,0,1,3,2,1,2,1]", out: "6" }, { in: "height = [4,2,0,3,2,5]", out: "9" }],
    constraints: ["1 ≤ n ≤ 2·10⁴", "0 ≤ height[i] ≤ 10⁵"],
    tests: [{ in: "[0,1,0,2,1,0,1,3,2,1,2,1]", out: "6" }, { in: "[4,2,0,3,2,5]", out: "9" }, { in: "[3]", out: "0" }],
    approach: "Water above bar i is min(maxLeft, maxRight) − height[i]. Two pointers from both ends: always move the side with the smaller max, because that side's water level is already decided.",
    time: "O(n)", space: "O(1)",
    solution: { python: "def trap(h):\n    l, r = 0, len(h) - 1\n    lmax = rmax = water = 0\n    while l < r:\n        if h[l] < h[r]:\n            lmax = max(lmax, h[l]); water += lmax - h[l]; l += 1\n        else:\n            rmax = max(rmax, h[r]); water += rmax - h[r]; r -= 1\n    return water", cpp: "int trap(const vector<int>& h) {\n    int l = 0, r = (int)h.size() - 1, lmax = 0, rmax = 0, water = 0;\n    while (l < r) {\n        if (h[l] < h[r]) { lmax = max(lmax, h[l]); water += lmax - h[l++]; }\n        else            { rmax = max(rmax, h[r]); water += rmax - h[r--]; }\n    }\n    return water;\n}" }
  },
  37: {
    desc: "Given an array of size n, find the element that appears more than ⌊n/2⌋ times. If no such element exists, return -1.",
    input: "An integer array arr.", output: "The majority element, or -1.",
    examples: [{ in: "arr = [3, 1, 3, 3, 2]", out: "3" }, { in: "arr = [1, 2, 3]", out: "-1" }],
    constraints: ["1 ≤ n ≤ 10⁵", "0 ≤ arr[i] ≤ 10⁵"],
    tests: [{ in: "[3,1,3,3,2]", out: "3" }, { in: "[1,2,3]", out: "-1" }, { in: "[7]", out: "7" }],
    approach: "Boyer–Moore voting: keep a candidate and a counter. Increment for matches and decrement otherwise, and adopt a new candidate when the counter hits 0. A second pass verifies the candidate really appears more than n/2 times.",
    time: "O(n)", space: "O(1)",
    solution: { python: "def majority(arr):\n    cand, cnt = None, 0\n    for x in arr:\n        if cnt == 0:\n            cand = x\n        cnt += 1 if x == cand else -1\n    return cand if arr.count(cand) > len(arr) // 2 else -1", java: "int majority(int[] a) {\n    int cand = 0, cnt = 0;\n    for (int x : a) { if (cnt == 0) cand = x; cnt += (x == cand) ? 1 : -1; }\n    int c = 0; for (int x : a) if (x == cand) c++;\n    return c > a.length / 2 ? cand : -1;\n}" }
  },
  38: {
    desc: "Given an integer array, find the contiguous subarray (containing at least one number) with the largest sum and return that sum.",
    input: "An integer array arr.", output: "The maximum subarray sum.",
    examples: [{ in: "arr = [-2,1,-3,4,-1,2,1,-5,4]", out: "6", note: "[4,-1,2,1] has sum 6" }, { in: "arr = [-3,-1,-2]", out: "-1" }],
    constraints: ["1 ≤ n ≤ 10⁵", "-10⁴ ≤ arr[i] ≤ 10⁴"],
    tests: [{ in: "[-2,1,-3,4,-1,2,1,-5,4]", out: "6" }, { in: "[-3,-1,-2]", out: "-1" }, { in: "[5,4,-1,7,8]", out: "23" }],
    approach: "Kadane's algorithm: at each index, the best subarray ending here either extends the previous one or starts fresh, so cur = max(x, cur + x). Track the best cur seen. Initialise with the first element so all-negative arrays work.",
    time: "O(n)", space: "O(1)",
    solution: { python: "def max_subarray(arr):\n    best = cur = arr[0]\n    for x in arr[1:]:\n        cur = max(x, cur + x)\n        best = max(best, cur)\n    return best", cpp: "long long maxSubarray(const vector<int>& a) {\n    long long best = a[0], cur = a[0];\n    for (size_t i = 1; i < a.size(); ++i) {\n        cur = max<long long>(a[i], cur + a[i]);\n        best = max(best, cur);\n    }\n    return best;\n}" }
  },
  58: {
    desc: "Given a sorted array of distinct integers and a target value, return the index of the target, or -1 if it isn't present. Your solution must run in O(log n).",
    input: "A sorted integer array arr and an integer target.", output: "The index of target, or -1.",
    examples: [{ in: "arr = [-1,0,3,5,9,12], target = 9", out: "4" }, { in: "arr = [-1,0,3,5,9,12], target = 2", out: "-1" }],
    constraints: ["1 ≤ n ≤ 10⁵", "arr is sorted in ascending order"],
    tests: [{ in: "[-1,0,3,5,9,12], 9", out: "4" }, { in: "[-1,0,3,5,9,12], 2", out: "-1" }, { in: "[5], 5", out: "0" }],
    approach: "Binary search on an inclusive range [lo, hi] that starts at [0, n−1]. Compare arr[mid] with target and discard half each step. Compute mid as lo + (hi − lo) / 2 to avoid integer overflow.",
    time: "O(log n)", space: "O(1)",
    solution: { python: "def search(arr, target):\n    lo, hi = 0, len(arr) - 1\n    while lo <= hi:\n        mid = lo + (hi - lo) // 2\n        if arr[mid] == target:\n            return mid\n        if arr[mid] < target:\n            lo = mid + 1\n        else:\n            hi = mid - 1\n    return -1", java: "int search(int[] a, int t) {\n    int lo = 0, hi = a.length - 1;\n    while (lo <= hi) {\n        int mid = lo + (hi - lo) / 2;\n        if (a[mid] == t) return mid;\n        if (a[mid] < t) lo = mid + 1; else hi = mid - 1;\n    }\n    return -1;\n}" }
  },
  92: {
    desc: "Given the head of a singly linked list, reverse the list and return the new head.",
    input: "The head node of a linked list.", output: "The head of the reversed list.",
    examples: [{ in: "1 → 2 → 3 → 4 → 5", out: "5 → 4 → 3 → 2 → 1" }, { in: "(empty list)", out: "(empty list)" }],
    constraints: ["0 ≤ number of nodes ≤ 10⁴"],
    tests: [{ in: "1->2->3->4->5", out: "5->4->3->2->1" }, { in: "1->2", out: "2->1" }, { in: "(empty)", out: "(empty)" }],
    approach: "Iterative: keep prev = null. For each node, save next, point node.next at prev, then advance prev and node. The recursive version reverses the rest first and then hooks the current node on the end, but it uses O(n) stack.",
    time: "O(n)", space: "O(1) iterative, O(n) recursive",
    solution: { python: "def reverse(head):\n    prev = None\n    while head:\n        nxt = head.next\n        head.next = prev\n        prev, head = head, nxt\n    return prev", java: "ListNode reverse(ListNode head) {\n    ListNode prev = null;\n    while (head != null) {\n        ListNode nxt = head.next;\n        head.next = prev;\n        prev = head;\n        head = nxt;\n    }\n    return prev;\n}" }
  },
  127: {
    desc: "Given a string containing only the characters ( ) { } [ ], determine whether the brackets are balanced: every opening bracket is closed by the same type, in the correct order.",
    input: "A string s.", output: "true if balanced, otherwise false.",
    examples: [{ in: "s = \"{([])}\"", out: "true" }, { in: "s = \"([)]\"", out: "false" }],
    constraints: ["1 ≤ s.length ≤ 10⁵"],
    tests: [{ in: "\"{([])}\"", out: "true" }, { in: "\"([)]\"", out: "false" }, { in: "\"((\"", out: "false" }, { in: "\")\"", out: "false" }],
    approach: "Push each opening bracket onto a stack. For a closing bracket, the stack must be non-empty and its top must be the matching opener. At the end, the stack must be empty.",
    time: "O(n)", space: "O(n)",
    solution: { python: "def balanced(s):\n    pairs = {')': '(', ']': '[', '}': '{'}\n    st = []\n    for c in s:\n        if c in '([{':\n            st.append(c)\n        elif not st or st.pop() != pairs[c]:\n            return False\n    return not st", javascript: "function balanced(s) {\n  const pairs = { ')': '(', ']': '[', '}': '{' };\n  const st = [];\n  for (const c of s) {\n    if ('([{'.includes(c)) st.push(c);\n    else if (st.pop() !== pairs[c]) return false;\n  }\n  return st.length === 0;\n}" }
  },
  170: {
    desc: "Given a string s, find the length of the longest substring without repeating characters.",
    input: "A string s.", output: "An integer length.",
    examples: [{ in: "s = \"abcabcbb\"", out: "3", note: "\"abc\"" }, { in: "s = \"bbbbb\"", out: "1" }],
    constraints: ["0 ≤ s.length ≤ 5·10⁴"],
    tests: [{ in: "\"abcabcbb\"", out: "3" }, { in: "\"bbbbb\"", out: "1" }, { in: "\"pwwkew\"", out: "3" }, { in: "\"\"", out: "0" }],
    approach: "Variable sliding window. Store each character's last index. When a repeat appears inside the window, move the left edge just past its previous position. The answer is the widest window seen.",
    time: "O(n)", space: "O(min(n, alphabet))",
    solution: { python: "def longest_unique(s):\n    last, left, best = {}, 0, 0\n    for i, c in enumerate(s):\n        if c in last and last[c] >= left:\n            left = last[c] + 1\n        last[c] = i\n        best = max(best, i - left + 1)\n    return best", go: "func longestUnique(s string) int {\n    last := map[rune]int{}\n    left, best := 0, 0\n    for i, c := range s {\n        if j, ok := last[c]; ok && j >= left {\n            left = j + 1\n        }\n        last[c] = i\n        if i-left+1 > best {\n            best = i - left + 1\n        }\n    }\n    return best\n}" }
  },
  265: {
    desc: "Given an m × n grid of '1' (land) and '0' (water), count the number of islands. An island is a group of land cells connected horizontally or vertically.",
    input: "A 2-D character grid.", output: "The number of islands.",
    examples: [{ in: "[[1,1,0,0],[1,0,0,1],[0,0,1,1]]", out: "2" }],
    constraints: ["1 ≤ m, n ≤ 300"],
    tests: [{ in: "[[1,1,0,0],[1,0,0,1],[0,0,1,1]]", out: "2" }, { in: "[[0]]", out: "0" }, { in: "[[1,0,1],[0,1,0],[1,0,1]]", out: "5" }],
    approach: "Scan every cell. When you find unvisited land, count a new island and flood-fill (BFS or DFS) to mark its whole component as visited. BFS with an explicit queue avoids recursion-depth limits on large grids.",
    time: "O(m·n)", space: "O(m·n) worst case for the queue",
    solution: { python: "from collections import deque\n\ndef num_islands(g):\n    m, n, count = len(g), len(g[0]), 0\n    for i in range(m):\n        for j in range(n):\n            if g[i][j] == '1':\n                count += 1\n                g[i][j] = '0'\n                q = deque([(i, j)])\n                while q:\n                    r, c = q.popleft()\n                    for dr, dc in ((1,0),(-1,0),(0,1),(0,-1)):\n                        x, y = r + dr, c + dc\n                        if 0 <= x < m and 0 <= y < n and g[x][y] == '1':\n                            g[x][y] = '0'\n                            q.append((x, y))\n    return count" }
  },
  335: {
    desc: "There are n stairs. You can climb 1 or 2 stairs at a time. Count the number of distinct ordered ways to reach the top, modulo 10⁹ + 7.",
    input: "An integer n.", output: "The number of ways, modulo 10⁹ + 7.",
    examples: [{ in: "n = 4", out: "5", note: "1111, 112, 121, 211, 22" }, { in: "n = 1", out: "1" }],
    constraints: ["1 ≤ n ≤ 10⁵"],
    tests: [{ in: "4", out: "5" }, { in: "1", out: "1" }, { in: "10", out: "89" }],
    approach: "ways(n) = ways(n−1) + ways(n−2), because the last step was either 1 or 2. The plain recursion is exponential. Keep only the last two values and iterate upward.",
    time: "O(n)", space: "O(1)",
    solution: { python: "def count_ways(n, mod=10**9 + 7):\n    a, b = 1, 1  # ways(0), ways(1)\n    for _ in range(n - 1):\n        a, b = b, (a + b) % mod\n    return b", c: "long long countWays(int n) {\n    const long long MOD = 1000000007LL;\n    long long a = 1, b = 1;\n    for (int i = 2; i <= n; i++) { long long c = (a + b) % MOD; a = b; b = c; }\n    return b;\n}" }
  }
};
