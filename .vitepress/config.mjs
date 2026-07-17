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
          { text: 'Safety Model', link: '/advanced/safety-model' },
          { text: 'Compile-Time Introspection', link: '/advanced/introspection' }
        ]
      },
      { text: 'GitHub', link: 'https://github.com/safec-org/SafeC' }
    ],
    sidebar: {
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
