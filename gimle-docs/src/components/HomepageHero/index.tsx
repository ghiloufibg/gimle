import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

export default function HomepageHero(): ReactNode {
  return (
    <header className={styles.hero}>
      <div className={styles.heroInner}>
        <p className={styles.eyebrow}>Karaf/OSGi module lifecycle × Kubernetes-style orchestration</p>
        <Heading as="h1" className={styles.title}>
          Gimlé
        </Heading>
        <p className={styles.tagline}>
          A fully-Java application platform: dynamic module lifecycle, self-healing, scaling, and
          service discovery, implemented entirely on the JVM. No containers, no external
          orchestrator, no non-Java runtime.
        </p>
        <div className={styles.actions}>
          <Link className="button button--primary button--lg" to="/docs/tutorials/getting-started">
            Get Started
          </Link>
          <Link
            className="button button--secondary button--lg"
            to="/docs/architecture/tiered-isolation">
            Read the Architecture
          </Link>
          <Link className="button button--secondary button--lg" to="pathname:///javadoc/">
            API Reference
          </Link>
        </div>
      </div>
    </header>
  );
}
