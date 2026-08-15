const base = require('./app.json').expo

/** @param {{ config: import('@expo/config-types').ExpoConfig }} _context */
module.exports = function appConfig(_context) {
  const development = process.env.APP_VARIANT === 'development'
  const suffix = development ? '-dev' : ''

  return {
    ...base,
    name: development ? 'NGA 阅读器 Dev' : base.name,
    scheme: development ? 'ng2-dev' : base.scheme,
    icon: `./assets/images/icon${suffix}.png`,
    android: {
      ...base.android,
      package: development ? 'com.chasel.ng2.dev' : base.android.package,
      adaptiveIcon: {
        ...base.android.adaptiveIcon,
        backgroundColor: '#FCF4E1',
        foregroundImage: `./assets/images/android-icon-foreground${suffix}.png`,
        backgroundImage: `./assets/images/android-icon-background${suffix}.png`,
        monochromeImage: `./assets/images/android-icon-monochrome${suffix}.png`,
      },
    },
    plugins: base.plugins.map((plugin) => {
      if (!Array.isArray(plugin) || plugin[0] !== 'expo-splash-screen') return plugin
      return [
        'expo-splash-screen',
        {
          ...plugin[1],
          image: `./assets/images/splash-icon${suffix}.png`,
          dark: {
            ...plugin[1].dark,
            image: `./assets/images/splash-icon-dark${suffix}.png`,
          },
        },
      ]
    }),
    extra: {
      ...base.extra,
      appVariant: development ? 'development' : 'release',
    },
  }
}
