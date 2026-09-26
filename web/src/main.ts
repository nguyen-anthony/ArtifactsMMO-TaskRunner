import { mount } from 'svelte'
import App from './App.svelte'
import './app.css'

// Svelte 5: `mount` renders the root component into the page.
mount(App, { target: document.getElementById('app')! })
