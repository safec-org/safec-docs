import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'SafeC',
  description: 'The SafeC Programming Language — Safe, Deterministic Systems Programming',
  base: '/safec-docs/',
  head: [
    ['link', { rel: 'icon', type: 'image/x-icon', href: '/safec-docs/favicon.ico' }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '32x32', href: '/safec-docs/favicon-32x32.png' }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '16x16', href: '/safec-docs/favicon-16x16.png' }],
    ['link', { rel: 'apple-touch-icon', href: '/safec-docs/apple-touch-icon.png' }],
    ['link', { rel: 'manifest', href: '/safec-docs/site.webmanifest' }]
  ],
  themeConfig: {
    logo: '/Logo.svg',
    nav: [
      {
        text: 'Book',
        items: [
          { text: 'Preface', link: '/book/' },
          { text: '1. Getting Started', link: '/book/ch01-getting-started' },
          { text: '2. A First Program', link: '/book/ch02-first-program' },
          { text: '3. Common Concepts', link: '/book/ch03-common-concepts' },
          { text: '4. Functions', link: '/book/ch04-functions' },
          { text: '5. Understanding Regions', link: '/book/ch05-understanding-regions' },
          { text: '6. Structs and Methods', link: '/book/ch06-structs-and-methods' },
          { text: '7. Enums, Unions, and Match', link: '/book/ch07-enums-and-match' },
          { text: '8. Error Handling', link: '/book/ch08-error-handling' },
          { text: '9. Final Project', link: '/book/ch09-final-project' }
        ]
      },
      {
        text: 'Guide',
        items: [
          { text: 'Introduction', link: '/guide/introduction' },
          { text: 'Getting Started', link: '/guide/getting-started' },
          { text: 'Design Philosophy', link: '/guide/design' },
          { text: 'Comparison', link: '/guide/comparison' }
        ]
      },
      {
        text: 'Reference',
        items: [
          { text: 'Types', link: '/reference/types' },
          { text: 'Operators', link: '/reference/operators' },
          { text: 'Literals & Qualifiers', link: '/reference/literals' },
          { text: 'Memory & Regions', link: '/reference/memory' },
          { text: 'Functions', link: '/reference/functions' },
          { text: 'Generics', link: '/reference/generics' },
          { text: 'Namespaces', link: '/reference/namespaces' },
          { text: 'Native SIMD', link: '/reference/simd' },
          { text: 'C Superset', link: '/reference/c-superset' },
          { text: 'Control Flow', link: '/reference/control-flow' },
          { text: 'Safety', link: '/reference/safety' },
          { text: 'C Interop (FFI)', link: '/reference/ffi' },
          { text: 'Concurrency', link: '/reference/concurrency' },
          { text: 'Bare-Metal', link: '/reference/baremetal' },
          { text: 'Preprocessor', link: '/reference/preprocessor' },
          { text: 'Overflow Operators', link: '/reference/overflow' }
        ]
      },
      {
        text: 'Standard Library',
        items: [
          { text: 'Overview', link: '/stdlib/' },
          { text: 'mem', link: '/stdlib/mem' },
          { text: 'io', link: '/stdlib/io' },
          { text: 'str', link: '/stdlib/str' },
          { text: 'math', link: '/stdlib/math' },
          { text: 'thread', link: '/stdlib/thread' },
          { text: 'atomic', link: '/stdlib/atomic' },
          { text: 'Serialization', link: '/stdlib/serial' },
          { text: 'Collections', link: '/stdlib/collections' },
          { text: 'Allocators', link: '/stdlib/allocators' },
          { text: 'Synchronization', link: '/stdlib/sync' },
          { text: 'IPC', link: '/stdlib/ipc' },
          { text: 'Real-Time Scheduler', link: '/stdlib/sched' },
          { text: 'Networking', link: '/stdlib/net' },
          { text: 'Filesystems', link: '/stdlib/fs' },
          { text: 'DSP & Real-Time', link: '/stdlib/dsp' },
          { text: 'Cryptography', link: '/stdlib/crypto' },
          { text: 'Debugging', link: '/stdlib/debug' },
          { text: 'SIMD', link: '/stdlib/simd' },
          { text: 'Hardware Abstraction', link: '/stdlib/hal' },
          { text: 'Interrupts & MMIO', link: '/stdlib/interrupt' },
          { text: 'Kernel Primitives', link: '/stdlib/kernel' },
          { text: 'Testing & Benchmarking', link: '/stdlib/testing' }
        ]
      },
      {
        text: 'Advanced',
        items: [
          { text: 'Compiler Architecture', link: '/advanced/compiler' },
          { text: 'Package Manager', link: '/advanced/safeguard' },
          { text: 'scx Templating', link: '/advanced/scx' },
          { text: 'Safety Model', link: '/advanced/safety-model' },
          { text: 'Compile-Time Introspection', link: '/advanced/introspection' }
        ]
      },
      { text: 'GitHub', link: 'https://github.com/safec-org/SafeC' }
    ],
    sidebar: {
      '/book/': [
        {
          text: 'The SafeC Book',
          items: [
            { text: 'Preface', link: '/book/' },
            { text: '1. Getting Started', link: '/book/ch01-getting-started' },
            { text: '2. A First Program', link: '/book/ch02-first-program' },
            { text: '3. Common Concepts', link: '/book/ch03-common-concepts' },
            { text: '4. Functions', link: '/book/ch04-functions' },
            { text: '5. Understanding Regions', link: '/book/ch05-understanding-regions' },
            { text: '6. Structs and Methods', link: '/book/ch06-structs-and-methods' },
            { text: '7. Enums, Unions, and Match', link: '/book/ch07-enums-and-match' },
            { text: '8. Error Handling', link: '/book/ch08-error-handling' },
            { text: '9. Final Project', link: '/book/ch09-final-project' }
          ]
        }
      ],
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/introduction' },
            { text: 'Getting Started', link: '/guide/getting-started' },
            { text: 'Design Philosophy', link: '/guide/design' },
            { text: 'Comparison', link: '/guide/comparison' }
          ]
        }
      ],
      '/reference/': [
        {
          text: 'Language Reference',
          items: [
            { text: 'Types', link: '/reference/types' },
            { text: 'Operators', link: '/reference/operators' },
            { text: 'Literals & Qualifiers', link: '/reference/literals' },
            { text: 'Memory & Regions', link: '/reference/memory' },
            { text: 'Functions', link: '/reference/functions' },
            { text: 'Generics', link: '/reference/generics' },
            { text: 'Namespaces', link: '/reference/namespaces' },
            { text: 'Native SIMD', link: '/reference/simd' },
            { text: 'C Superset', link: '/reference/c-superset' },
            { text: 'Control Flow', link: '/reference/control-flow' },
            { text: 'Safety', link: '/reference/safety' },
            { text: 'C Interop (FFI)', link: '/reference/ffi' },
            { text: 'Concurrency', link: '/reference/concurrency' },
            { text: 'Bare-Metal', link: '/reference/baremetal' },
            { text: 'Preprocessor', link: '/reference/preprocessor' },
            { text: 'Overflow Operators', link: '/reference/overflow' }
          ]
        }
      ],
      '/stdlib/': [
        {
          text: 'Standard Library',
          items: [
            { text: 'Overview', link: '/stdlib/' },
          ]
        },
        {
          text: 'Core',
          items: [
            { text: 'mem', link: '/stdlib/mem' },
            { text: 'io', link: '/stdlib/io' },
            { text: 'str', link: '/stdlib/str' },
            { text: 'math', link: '/stdlib/math' },
            { text: 'thread', link: '/stdlib/thread' },
            { text: 'atomic', link: '/stdlib/atomic' },
          ]
        },
        {
          text: 'Serialization',
          items: [
            { text: 'JSON / XML / HTML', link: '/stdlib/serial' },
          ]
        },
        {
          text: 'Collections',
          items: [
            { text: 'All Collections', link: '/stdlib/collections' },
          ]
        },
        {
          text: 'Ecosystem',
          items: [
            { text: 'Allocators', link: '/stdlib/allocators' },
            { text: 'Synchronization', link: '/stdlib/sync' },
            { text: 'IPC', link: '/stdlib/ipc' },
            { text: 'Real-Time Scheduler', link: '/stdlib/sched' },
            { text: 'Networking', link: '/stdlib/net' },
            { text: 'Filesystems', link: '/stdlib/fs' },
            { text: 'DSP & Real-Time', link: '/stdlib/dsp' },
            { text: 'Cryptography', link: '/stdlib/crypto' },
            { text: 'Debugging', link: '/stdlib/debug' },
          ]
        },
        {
          text: 'SIMD & Embedded',
          items: [
            { text: 'SIMD', link: '/stdlib/simd' },
            { text: 'Hardware Abstraction', link: '/stdlib/hal' },
            { text: 'Interrupts & MMIO', link: '/stdlib/interrupt' },
            { text: 'Kernel Primitives', link: '/stdlib/kernel' },
          ]
        },
        {
          text: 'Development',
          items: [
            { text: 'Testing & Benchmarking', link: '/stdlib/testing' },
          ]
        },
      ],
      '/advanced/': [
        {
          text: 'Advanced',
          items: [
            { text: 'Compiler Architecture', link: '/advanced/compiler' },
            { text: 'Package Manager', link: '/advanced/safeguard' },
            { text: 'scx Templating', link: '/advanced/scx' },
            { text: 'Safety Model', link: '/advanced/safety-model' },
            { text: 'Compile-Time Introspection', link: '/advanced/introspection' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/safec-org/SafeC' }
    ],
    search: { provider: 'local' },
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright 2026 SafeC Contributors'
    }
  }
})
