/**
 * The JSR-250 annotations that shipped with Java 8 ({@code java.xml.ws.annotation}) and were removed
 * in Java 11. Mixin 0.7 checks LaunchWrapper transformers for {@link javax.annotation.Resource}, so
 * without these every Mixin-based 1.8.9 mod fails to start. The agent jar is on the system class
 * path, where LaunchWrapper delegates {@code javax.*}, so all class loaders see this copy.
 */
package javax.annotation;
