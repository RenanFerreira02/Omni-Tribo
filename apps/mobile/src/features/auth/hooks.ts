import { useMutation, useQueryClient } from '@tanstack/react-query';

import { login, me, registrar, type DadosRegistro } from '@/api/auth';
import type { ErroApi } from '@/api/erros';
import { useSessao } from '@/stores/sessao';

export function useLogin() {
  const definirSessao = useSessao((estado) => estado.definirSessao);
  const definirUsuario = useSessao((estado) => estado.definirUsuario);
  const queryClient = useQueryClient();

  return useMutation<void, ErroApi, { email: string; senha: string }>({
    mutationFn: async ({ email, senha }) => {
      const tokens = await login(email, senha);
      await definirSessao(tokens);
      // Só agora: `me` precisa do access token que acabou de entrar no store.
      definirUsuario(await me());
      // Cache do usuário anterior não pode sobreviver a uma troca de conta — seria saldo alheio na
      // tela até o primeiro refetch.
      queryClient.clear();
    },
  });
}

export function useRegistro() {
  const definirSessao = useSessao((estado) => estado.definirSessao);
  const definirUsuario = useSessao((estado) => estado.definirUsuario);
  const queryClient = useQueryClient();

  return useMutation<void, ErroApi, DadosRegistro>({
    mutationFn: async (dados) => {
      // O backend já devolve o par de tokens no registro: o usuário entra direto, sem uma segunda
      // viagem ao login.
      const tokens = await registrar(dados);
      await definirSessao(tokens);
      definirUsuario(await me());
      // MESMA limpeza do login, e ela faltava aqui. As chaves de query são globais (`['perfil']`,
      // `['carteira','saldo']`), então quem se registrasse num aparelho onde outra pessoa tinha
      // usado o app veria o nome, o e-mail e o SALDO dela até o primeiro refetch. Entrar por
      // "criar conta" não é um caminho menos sensível que entrar por "login".
      queryClient.clear();
    },
  });
}
