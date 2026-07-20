use std::thread;

const NTHREADS: i32 = 8;

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
        let mut per_thread = iterations / NTHREADS;
        if per_thread < 1 { per_thread = 1; }

        let mut handles = Vec::new();
        let mut start = 0;
        let mut n_spawned = 0;
        let mut t = 0;
        while t < NTHREADS && start < iterations {
            let mut end = start + per_thread;
            if t == NTHREADS - 1 || end > iterations { end = iterations; }
            let s = start;
            let e = end;
            handles.push(thread::spawn(move || {
                let mut check = 0;
                for _ in s..e {
                    let tree = make_tree(depth);
                    check += checksum(&tree);
                }
                check
            }));
            n_spawned += 1;
            start = end;
            t += 1;
        }
        let mut check = 0;
        for _ in 0..n_spawned {
            check += handles.remove(0).join().unwrap();
        }
        println!("{} trees of depth {} check: {}", iterations, depth, check);
        depth += 2;
    }

    println!("long lived tree of depth {} check: {}", max_depth, checksum(&long_lived_tree));
}
