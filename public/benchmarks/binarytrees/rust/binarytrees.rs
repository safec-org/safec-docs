struct Node {
    left: Option<Box<Node>>,
    right: Option<Box<Node>>,
}

fn make_tree(depth: i32) -> Box<Node> {
    if depth > 0 {
        Box::new(Node {
            left: Some(make_tree(depth - 1)),
            right: Some(make_tree(depth - 1)),
        })
    } else {
        Box::new(Node { left: None, right: None })
    }
}

fn checksum(n: &Node) -> i32 {
    match (&n.left, &n.right) {
        (Some(l), Some(r)) => 1 + checksum(l) + checksum(r),
        _ => 1,
    }
}

fn main() {
    let min_depth = 4;
    let max_depth = 18;

    let stretch_depth = max_depth + 1;
    let stretch_tree = make_tree(stretch_depth);
    println!("stretch tree of depth {} check: {}", stretch_depth, checksum(&stretch_tree));
    drop(stretch_tree);

    let long_lived_tree = make_tree(max_depth);

    let mut depth = min_depth;
    while depth <= max_depth {
        let iterations = 1i32 << (max_depth - depth + min_depth);
        let mut check = 0;
        for _ in 0..iterations {
            let t = make_tree(depth);
            check += checksum(&t);
        }
        println!("{} trees of depth {} check: {}", iterations, depth, check);
        depth += 2;
    }

    println!("long lived tree of depth {} check: {}", max_depth, checksum(&long_lived_tree));
}
