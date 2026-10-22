class Node:
    __slots__ = ("left", "right")
    def __init__(self, left, right):
        self.left = left
        self.right = right

def make_tree(depth):
    if depth > 0:
        return Node(make_tree(depth - 1), make_tree(depth - 1))
    return Node(None, None)

def checksum(n):
    if n.left is None:
        return 1
    return 1 + checksum(n.left) + checksum(n.right)

min_depth = 4
max_depth = 18

stretch_depth = max_depth + 1
stretch_tree = make_tree(stretch_depth)
print(f"stretch tree of depth {stretch_depth} check: {checksum(stretch_tree)}")
stretch_tree = None

long_lived_tree = make_tree(max_depth)

depth = min_depth
while depth <= max_depth:
    iterations = 1 << (max_depth - depth + min_depth)
    check = 0
    for _ in range(iterations):
        t = make_tree(depth)
        check += checksum(t)
    print(f"{iterations} trees of depth {depth} check: {check}")
    depth += 2

print(f"long lived tree of depth {max_depth} check: {checksum(long_lived_tree)}")
