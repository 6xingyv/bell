// SPDX-License-Identifier: LicenseRef-Mocha-1.0
import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'

const run = promisify(execFile)
const destination = resolve(process.argv[2] ?? 'dist/screenshots/music')
const downloads = join(destination, '..', 'recordings')
const commons = (name, hash) => {
  const path = createHash('md5').update(name).digest('hex').slice(0, 2)
  return {
    url: 'https://upload.wikimedia.org/wikipedia/commons/' + path[0] + '/' + path + '/' + encodeURIComponent(name),
    credit: 'https://commons.wikimedia.org/wiki/File:' + encodeURIComponent(name),
    algorithm: 'sha1',
    hash,
  }
}
const jukebox = (id, hash) => ({
  url: 'https://data.labs.loc.gov/jukebox/audio/jukebox-' + id + '_1.mp3',
  credit: 'https://www.loc.gov/item/jukebox-' + id + '/',
  algorithm: 'md5',
  hash,
})

const albums = [
  {
    title: 'Harvest Moon', artist: 'Ada Jones; Billy Murray', genre: 'Vaudeville', color: [15, 31, 75], ink: [255, 192, 112],
    tracks: [
      ['Shine On, Harvest Moon', 1909, ['Ada Jones', 'Billy Murray'], commons('Ada_Jones_and_Billy_Murray_-_Shine_On,_Harvest_Moon_(unrestored).ogg', '3b7500e12bb0ffb59a0f0ec66f8376ec5ff80394')],
      ['He Wants Someone to Call Him Papa', 1913, ['Ada Jones', 'Billy Murray'], jukebox(133623, 'ba4aeab4fe2aea7c4830f03697796642')],
    ],
  },
  {
    title: 'Flying Machine', artist: 'Blanche Ring', genre: 'Vaudeville', color: [29, 121, 139], ink: [255, 227, 159],
    tracks: [['Come, Josephine in My Flying Machine', 1910, ['Blanche Ring'], commons('Come,_Josephine_in_my_flying_machine_(1910,_Ring).mp3', 'db6d0b2c7eac6ce51b490c30b3c679a1f7069abb')]],
  },
  {
    title: 'Auld Lang Syne', artist: 'Bautsch', genre: 'Folk', color: [50, 37, 94], ink: [214, 169, 242],
    tracks: [['Auld Lang Syne', 2025, ['Bautsch'], commons('AuldLangSyne.ogg', '1cf4acdffc1c85e3d3b0ef94be8922cec28c61e2')]],
  },
  {
    title: 'Livery Stable Blues', artist: 'Original Dixieland Jazz Band', genre: 'Jazz', color: [203, 80, 41], ink: [255, 217, 135],
    tracks: [['Livery Stable Blues', 1917, ['Original Dixieland Jazz Band'], jukebox(186254, '25fcd6282d7005b98321b58a15b958c9')]],
  },
  {
    title: 'The Memphis Blues', artist: 'Victor Military Band', genre: 'Blues', color: [24, 93, 123], ink: [180, 230, 212],
    tracks: [['The Memphis Blues', 1914, ['Victor Military Band'], jukebox(275361, 'b416a53aa7a4471d6265843e27e0c247')]],
  },
  {
    title: 'Maple Leaf Rag', artist: 'United States Marine Band', genre: 'Ragtime', color: [133, 36, 50], ink: [248, 158, 104],
    tracks: [['Maple Leaf Rag', 1906, ['United States Marine Band'], commons("1906_-_Scott_Joplin's_Maple_Leaf_Rag_(1899)_played_by_the_United_States_Marine_Band.ogg", 'b76a36d2811a6aede6b3fe3c58c314ba501c4716')]],
  },
  {
    title: 'O Sole Mio', artist: 'Enrico Caruso', genre: 'Classical', color: [45, 92, 61], ink: [240, 218, 133],
    tracks: [['’O Sole Mio', 1916, ['Enrico Caruso'], jukebox(16387, '348e1a7c04bb92988b8ecbc9c4ff8d51')]],
  },
]

function cover(album, index) {
  const size = 512
  const pixels = Buffer.alloc(size * size * 3)
  for (let row = 0; row < size; row++) {
    for (let col = 0; col < size; col++) {
      const x = col / size
      const y = row / size
      const radius = Math.hypot(x - 0.5 - (index % 3 - 1) * 0.1, y - 0.44)
      const wave = Math.sin(y * 11 + x * (index + 3) + index) * 0.09
      const horizon = 0.65 + wave
      const orbit = Math.abs(radius - 0.22) < 0.035 || Math.abs(radius - 0.32) < 0.025
      const marks = [
        radius < 0.24 && Math.hypot(x - 0.29, y - 0.34) > 0.21,
        Math.abs(y - (0.62 - 0.5 * x)) < 0.04 && x > 0.12 && x < 0.88 ||
          Math.abs(x - 0.5) + Math.abs(y - 0.43) * 1.7 < 0.24,
        orbit,
        Math.abs(x - 0.5) + Math.abs(y - 0.49) < 0.37 && Math.abs(x - y + 0.06) > 0.08,
        y > 0.31 + x * 0.34 && y < 0.62 + x * 0.17,
        Math.hypot((x - 0.5) * 1.3, y - 0.45) < 0.29 && Math.abs(x - 0.5) > Math.abs(y - 0.43) * 0.25,
        radius < 0.15 || Math.abs(radius - 0.27) < 0.025,
      ]
      const groove = 0.5 + 0.5 * Math.cos(radius * (index === 0 ? 220 : 120))
      const grain = ((Math.imul(row + 45, col + 17) ^ Math.imul(row + 7, 3432918353)) & 31) / 31 - 0.5
      const mix = marks[index] ? 0.76 + groove * 0.16 : y > horizon ? 0.1 + groove * 0.18 : 0.08 + y * 0.18
      for (let channel = 0; channel < 3; channel++) {
        const color = album.color[channel] * (1 - mix) + album.ink[channel] * mix + grain * 13
        pixels[(row * size + col) * 3 + channel] = Math.max(0, Math.min(255, color))
      }
    }
  }
  return Buffer.concat([Buffer.from('P6\n512 512\n255\n'), pixels])
}

async function download(source, title) {
  const file = join(downloads, source.hash + extname(source.url))
  const valid = (bytes) => bytes && createHash(source.algorithm).update(bytes).digest('hex') === source.hash
  if (!valid(await readFile(file).catch(() => null))) {
    await run('curl', ['-fLsS', '--retry', '2', '--max-time', '40', '-A',
      'Bell screenshot fixture (https://github.com/6xingyv/bell)', '-o', file, source.url])
  }
  if (!valid(await readFile(file))) throw new Error('Unexpected asset checksum for ' + title)
  return file
}

await mkdir(downloads, { recursive: true })
await Promise.all(albums.map(async (album, index) => {
  const folder = join(destination, album.title)
  await mkdir(folder, { recursive: true })
  const artwork = join(downloads, 'cover-' + index + '.ppm')
  await writeFile(artwork, cover(album, index))
  await run('ffmpeg', ['-v', 'error', '-y', '-i', artwork, '-frames:v', '1', join(folder, 'folder.jpg')])
  await Promise.all(album.tracks.map(async ([title, year, artists, source], track) => {
    const recording = await download(source, title)
    const metadata = {
      title, album: album.title, album_artist: album.artist, artist: artists.join('; '),
      genre: album.genre, date: year, composer: title === 'Shine On, Harvest Moon' ? 'Nora Bayes' : '', track: track + 1, disc: 1, comment: source.credit,
    }
    await run('ffmpeg', [
      '-v', 'error', '-y', '-i', recording, '-map', '0:a:0', '-map_metadata', '-1',
      ...Object.entries(metadata).flatMap(([key, value]) => ['-metadata', key + '=' + value]),
      '-c:a', 'flac', join(folder, String(track + 1).padStart(2, '0') + ' - ' + title + '.flac'),
    ])
    console.log(title)
  }))
}))

const playerTrack = 'Shine On, Harvest Moon'
const lyricFile = new URL('./harvest-moon.ttml', import.meta.url)
await copyFile(lyricFile, join(destination, 'Harvest Moon', '01 - ' + playerTrack + '.ttml'))

const server = process.argv[3]
if (server) await seedServer(server)

async function seedServer(server) {
  const address = new URL(server)
  if (address.protocol !== 'http:' || address.hostname !== '127.0.0.1') {
    throw new Error('Use a dedicated local Jellyfin instance on 127.0.0.1.')
  }
  const client = 'MediaBrowser Client="Bell Screenshot", Device="Fixture", DeviceId="bell-screenshot", Version="1.0"'
  let authorization = client
  async function api(path, { method = 'GET', body, contentType = 'application/json' } = {}) {
    const response = await fetch(new URL(path, address), {
      method,
      headers: body ? { Authorization: authorization, 'Content-Type': contentType } : { Authorization: authorization },
      body: body && (contentType === 'application/json' ? JSON.stringify(body) : body),
      signal: AbortSignal.timeout(20_000),
    })
    if (!response.ok) throw new Error(method + ' ' + path + ' returned ' + response.status)
    if (response.headers.get('content-type')?.includes('application/json')) return response.json()
  }
  const post = (path, body) => api(path, { method: 'POST', body })

  let system
  for (let attempt = 1; ; attempt++) {
    try {
      system = await api('/System/Info/Public')
      break
    } catch (error) {
      const starting = error.message === 'GET /System/Info/Public returned 503'
        || error.cause?.code === 'ECONNREFUSED'
      if (!starting || attempt >= 60) throw error
      await new Promise(resolve => setTimeout(resolve, 2000))
    }
  }
  if (!system.StartupWizardCompleted) {
    await post('/Startup/Configuration', {
      ServerName: 'Bell Demo', UICulture: 'en-US', MetadataCountryCode: 'US', PreferredMetadataLanguage: 'en',
    })
    await api('/Startup/User')
    await post('/Startup/User', { Name: 'Demo', Password: 'bell-demo-preview' })
    await post('/Startup/Complete')
  }
  const { AccessToken, User } = await post('/Users/AuthenticateByName', {
    Username: 'Demo', Pw: 'bell-demo-preview',
  })
  authorization += ', Token="' + AccessToken + '"'
  const user = 'UserId=' + User.Id
  const libraries = await api('/Library/VirtualFolders')
  if (libraries.some((library) => library.Name !== 'Music' || !library.Locations.includes(destination))) {
    throw new Error('Jellyfin is serving a library outside the preview fixture.')
  }
  if (!libraries.length) {
    await post('/Library/VirtualFolders?name=Music&collectionType=music&refreshLibrary=false', {
      LibraryOptions: {
        PathInfos: [{ Path: destination }], EnableRealtimeMonitor: false, EnableLUFSScan: false,
        EnableInternetProviders: false, UseCustomTagDelimiters: true, CustomTagDelimiters: [';'],
        DisabledMetadataFetchers: ['MusicBrainz', 'TheAudioDB'], DisabledImageFetchers: ['TheAudioDB'],
      },
    })
  }

  await post('/Library/Refresh')
  const retryScanAt = Date.now() + 20_000
  let retriedScan = false
  const query = (type) => api('/Items?' + user + '&Recursive=true&IncludeItemTypes=' + type)
  const deadline = Date.now() + 120_000
  let audio
  let indexedAlbums
  let demo
  let indexed
  do {
    audio = (await query('Audio')).Items
    indexedAlbums = (await query('MusicAlbum')).Items
    demo = audio.find((track) => track.Name === playerTrack)
    indexed = audio.length === 8 && audio.every((track) => track.ArtistItems?.length) &&
        indexedAlbums.length === albums.length && indexedAlbums.every((album) => album.ImageTags?.Primary) &&
        demo?.ArtistItems?.length === 2
    if (indexed) break
    if (!retriedScan && !audio.length && Date.now() >= retryScanAt) {
      retriedScan = true
      await post('/Library/Refresh')
    }
    await delay(1_500)
  } while (Date.now() < deadline)
  if (!indexed) {
    throw new Error('Jellyfin did not index the expected recordings, performers and covers.')
  }

  const lyrics = await api('/Audio/' + demo.Id + '/Lyrics')
  if (!lyrics.Tracks?.some((track) => track.Lines?.some((line) => line.Syllables?.length > 1))) {
    throw new Error('Jellyfin did not return word-timed lyrics for ' + playerTrack)
  }

  const portraits = new Map([
    ['Ada Jones', commons('Ada_Jones.jpg', '90c749e41786207b6c7a23a37f3ee45d5c52dbf5')],
    ['Billy Murray', commons('BillyMurrayEdisonMay1911_crop.jpg', '943b8eff1ade3a230b80be0e017848899f274684')],
  ])
  await Promise.all(demo.ArtistItems.map(async ({ Name, Id }) => {
    const source = portraits.get(Name)
    if (!source) throw new Error('Unexpected performer ' + Name)
    const photo = await readFile(await download(source, Name))
    await api('/Items/' + Id + '/Images/Primary', {
      method: 'POST', contentType: 'image/jpeg', body: photo.toString('base64'),
    })
  }))

  const pins = ['Harvest Moon', 'Livery Stable Blues', 'Auld Lang Syne'].map((name) =>
    indexedAlbums.find((album) => album.Name === name).Id)
  const settingsPath = '/DisplayPreferences/jellify.music?' + user + '&Client=Jellify'
  const preferences = await api(settingsPath)
  await post(settingsPath, { ...preferences, CustomPrefs: { ...preferences.CustomPrefs, pins: JSON.stringify(pins) } })
  for (const [index, name] of ['Maple Leaf Rag', 'The Memphis Blues', 'Come, Josephine in My Flying Machine'].entries()) {
    const track = audio.find((item) => item.Name === name)
    await post('/UserPlayedItems/' + track.Id + '?' + user + '&DatePlayed=2026-01-01T10:0' + index + ':00Z')
  }
  console.log('Indexed eight recordings, album art, performer portraits, library pins and karaoke lyrics.')
}
