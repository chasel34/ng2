import { StyleSheet, Text, View } from 'react-native';

export default function Home() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>NGA 阅读器</Text>
      <Text style={styles.subtitle}>M1 骨架 · 等待首页实现</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#FCF4E1',
    gap: 8,
  },
  title: {
    fontSize: 20,
    fontWeight: '600',
    color: '#14796B',
  },
  subtitle: {
    fontSize: 13,
    color: '#A39D8E',
  },
});
