import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import styles from './styles.module.css';

type Feature = {
  title: string;
  tag: string;
  to: string;
  description: ReactNode;
};

const FEATURES: Feature[] = [
  {
    title: 'Tiered Isolation',
    tag: 'Tier 1 / 2 / 3',
    to: '/docs/architecture/tiered-isolation',
    description: (
      <>
        Container-grade isolation and classloader-grade density in one system, chosen per
        workload via the module manifest: a shared worker JVM for density, a dedicated worker JVM
        for a hard resource ceiling, or a Linux namespace for hostile-neighbour scenarios.
      </>
    ),
  },
  {
    title: 'Kubernetes-style Orchestration',
    tag: 'Self-healing · Scaling · Discovery',
    to: '/docs/architecture/control-plane',
    description: (
      <>
        Declarative desired state, level-triggered reconcilers, tiered self-healing, and a
        service fabric with locality-aware load balancing — the operational model, without the
        CRDs, <code>kubectl</code>, or OCI images.
      </>
    ),
  },
  {
    title: 'Zero Runtime Dependencies',
    tag: 'Pure JVM',
    to: '/docs/intro#what-gimlé-is-not',
    description: (
      <>
        No containers, no external orchestrator, no non-Java runtime anywhere in the stack. The
        module system, supervisor, control plane, and service fabric are implemented directly on
        the JDK.
      </>
    ),
  },
];

function FeatureCard({title, tag, to, description}: Feature): ReactNode {
  return (
    <div className={clsx('col col--4')}>
      <Link to={to} className={styles.card}>
        <p className={styles.cardTag}>{tag}</p>
        <h3>{title}</h3>
        <p>{description}</p>
      </Link>
    </div>
  );
}

export default function HomepageFeatures(): ReactNode {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FEATURES.map((feature) => (
            <FeatureCard key={feature.title} {...feature} />
          ))}
        </div>
      </div>
    </section>
  );
}
