// swagger-ui-admin-gated — deny-list enforcement + explanation seams.
// Not a security boundary (an ADMIN already holds a token and curl) — this
// stops a UI slip, nothing more.
import { denyReasonFor, isDenied, operationKey } from './nonExecutableOperations';

export { operationKey };

export function reasonForProps(props) {
  if (!props || !props.method || !props.path) return null;
  return denyReasonFor(props.method, props.path);
}

export function denyTryItOutPlugin() {
  return {
    statePlugins: {
      spec: {
        wrapActions: {
          execute: oriAction => args => {
            const { path, method } = args || {};
            if (isDenied(method, path)) return;
            return oriAction(args);
          },
        },
      },
    },
    wrapComponents: {
      OperationContainer: (Original, system) => {
        // swagger-ui's own `withConnect` reads `mapStateToProps` off
        // `Component.prototype` (its own convention, not react-redux's).
        // Copying only that one property — not `Original.prototype` itself,
        // which would also inherit `isReactComponent` and make React try to
        // `new` this as a class and call a `.render()` it doesn't have —
        // keeps this a plain function component while still resolving.
        function OperationContainerWithDenyReason(props) {
          const reason = reasonForProps(props);
          if (!reason) {
            return system.React.createElement(Original, props);
          }
          return system.React.createElement(
            system.React.Fragment,
            null,
            system.React.createElement(Original, { ...props, allowTryItOut: false }),
            system.React.createElement(
              'div',
              { className: 'api-docs-deny-reason', 'data-testid': `deny-reason-${operationKey(props.method, props.path)}` },
              reason,
            ),
          );
        }
        OperationContainerWithDenyReason.prototype = { mapStateToProps: Original.prototype.mapStateToProps };
        return OperationContainerWithDenyReason;
      },
    },
  };
}
